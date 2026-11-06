package dev.caiorocha.orderflow.ordering.application;

import dev.caiorocha.orderflow.catalog.domain.Product;
import dev.caiorocha.orderflow.catalog.domain.StockMovement;
import dev.caiorocha.orderflow.catalog.infrastructure.ProductRepository;
import dev.caiorocha.orderflow.catalog.infrastructure.StockMovementRepository;
import dev.caiorocha.orderflow.ordering.domain.Order;
import dev.caiorocha.orderflow.ordering.domain.OrderItem;
import dev.caiorocha.orderflow.ordering.domain.OrderStatus;
import dev.caiorocha.orderflow.ordering.domain.PaymentScenario;
import dev.caiorocha.orderflow.ordering.infrastructure.OrderRepository;
import dev.caiorocha.orderflow.shared.CurrentUser;
import dev.caiorocha.orderflow.shared.DomainException;
import dev.caiorocha.orderflow.shared.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final ProductRepository products;
    private final StockMovementRepository movements;
    private final OrderEventPublisher events;

    public OrderService(OrderRepository orders, ProductRepository products, StockMovementRepository movements,
                        OrderEventPublisher events) {
        this.orders = orders;
        this.products = products;
        this.movements = movements;
        this.events = events;
    }

    public record ItemRequest(Long productId, int quantity) {
    }

    public record CreateOrder(String customerName, String customerEmail, PaymentScenario paymentScenario,
                              List<ItemRequest> items, String idempotencyKey) {
    }

    /** Resultado da criação: {@code created=false} quando a mesma Idempotency-Key já gerou um pedido. */
    public record CreateResult(Order order, boolean created) {
    }

    /**
     * Cria o pedido em uma única transação: bloqueia os produtos, valida e reserva o estoque, calcula o
     * total com os preços do banco e grava o evento ORDER_CREATED no outbox. Se qualquer item falhar,
     * nada é reservado.
     */
    @Transactional
    public CreateResult create(CreateOrder cmd) {
        if (cmd.idempotencyKey() != null) {
            Optional<Order> existing = orders.findByIdempotencyKey(cmd.idempotencyKey());
            if (existing.isPresent()) {
                return new CreateResult(existing.get(), false);
            }
        }
        if (cmd.items() == null || cmd.items().isEmpty()) {
            throw new DomainException("EMPTY_ORDER", "O pedido precisa ter pelo menos um item");
        }
        Map<Long, Integer> quantities = cmd.items().stream()
                .collect(Collectors.toMap(ItemRequest::productId, ItemRequest::quantity, (a, b) -> {
                    throw new DomainException("DUPLICATE_ITEM", "O mesmo produto aparece mais de uma vez no pedido");
                }));

        // SELECT ... FOR UPDATE em ordem de id: pedidos concorrentes pelos mesmos produtos são serializados.
        Map<Long, Product> locked = products.findAllByIdForUpdate(quantities.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        quantities.keySet().stream().filter(id -> !locked.containsKey(id)).findFirst().ifPresent(id -> {
            throw new NotFoundException("Produto", id);
        });

        String user = CurrentUser.username();
        Order order = new Order(cmd.customerName(), cmd.customerEmail(),
                cmd.paymentScenario() == null ? PaymentScenario.APPROVE : cmd.paymentScenario(),
                cmd.idempotencyKey(), user);
        for (var item : cmd.items()) {
            Product product = locked.get(item.productId());
            product.reserve(item.quantity());
            order.addItem(product.getId(), product.getSku(), product.getName(), product.getPrice(), item.quantity());
        }
        orders.saveAndFlush(order);
        for (OrderItem item : order.getItems()) {
            movements.save(new StockMovement(item.getProductId(), order.getId(), StockMovement.Type.RESERVE,
                    item.getQuantity(), "Reserva do pedido " + order.code(), user));
        }
        events.publish(OrderEventType.ORDER_CREATED, order, null);
        log.info("Pedido {} criado com {} item(ns), total {}", order.code(), order.getItems().size(),
                order.getTotalAmount());
        return new CreateResult(order, true);
    }

    /**
     * Cancela um pedido pendente (libera a reserva) ou pago (estorno simulado e devolução ao estoque).
     * Operadores só cancelam os próprios pedidos; administradores cancelam qualquer um.
     */
    @Transactional
    public Order cancel(Long orderId, String reason) {
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new NotFoundException("Pedido", orderId));
        String user = CurrentUser.username();
        if (!CurrentUser.isAdmin() && !order.getCreatedBy().equals(user)) {
            throw new AccessDeniedException("Operadores só podem cancelar os próprios pedidos");
        }
        OrderStatus previous = order.getStatus();
        String why = reason == null || reason.isBlank() ? "Cancelado pelo usuário" : reason.trim();
        order.transitionTo(OrderStatus.CANCELLED, previous == OrderStatus.PAID ? why + " (estorno simulado)" : why, user);
        if (previous == OrderStatus.PENDING_PAYMENT) {
            releaseReservation(order, "Cancelamento do pedido " + order.code(), user);
        } else {
            restock(order, user);
        }
        events.publish(OrderEventType.ORDER_CANCELLED, order, why);
        return order;
    }

    /** Pagamento aprovado: a reserva vira consumo. Chamado pelo consumidor Kafka, na transação dele. */
    @Transactional
    public void confirmPayment(Order order, String paymentReference) {
        order.transitionTo(OrderStatus.PAID, "Pagamento simulado aprovado (" + paymentReference + ")", CurrentUser.SYSTEM);
        lockProducts(order).forEach((item, product) -> {
            product.consumeReservation(item.getQuantity());
            movements.save(new StockMovement(product.getId(), order.getId(), StockMovement.Type.CONSUME,
                    item.getQuantity(), "Pagamento aprovado do pedido " + order.code(), CurrentUser.SYSTEM));
        });
        events.publish(OrderEventType.ORDER_PAID, order, paymentReference);
    }

    /** Pagamento recusado ou falha definitiva: libera a reserva (compensação). */
    @Transactional
    public void failPayment(Order order, OrderStatus failureStatus, String reason) {
        order.transitionTo(failureStatus, reason, CurrentUser.SYSTEM);
        releaseReservation(order, reason + " (" + order.code() + ")", CurrentUser.SYSTEM);
        events.publish(failureStatus == OrderStatus.PAYMENT_DECLINED
                ? OrderEventType.ORDER_PAYMENT_DECLINED : OrderEventType.ORDER_PAYMENT_FAILED, order, reason);
    }

    @Transactional(readOnly = true)
    public Order get(Long id) {
        Order order = orders.findById(id).orElseThrow(() -> new NotFoundException("Pedido", id));
        order.getItems().size();
        order.getHistory().size();
        return order;
    }

    @Transactional(readOnly = true)
    public Page<Order> list(OrderStatus status, Pageable pageable) {
        return status == null ? orders.findAll(pageable) : orders.findByStatus(status, pageable);
    }

    private void releaseReservation(Order order, String reason, String user) {
        lockProducts(order).forEach((item, product) -> {
            product.releaseReservation(item.getQuantity());
            movements.save(new StockMovement(product.getId(), order.getId(), StockMovement.Type.RELEASE,
                    item.getQuantity(), reason, user));
        });
    }

    private void restock(Order order, String user) {
        lockProducts(order).forEach((item, product) -> {
            product.restock(item.getQuantity());
            movements.save(new StockMovement(product.getId(), order.getId(), StockMovement.Type.RETURN,
                    item.getQuantity(), "Devolução por cancelamento do pedido pago " + order.code(), user));
        });
    }

    /** Bloqueia os produtos do pedido na mesma ordem usada na criação (por id). */
    private Map<OrderItem, Product> lockProducts(Order order) {
        Map<Long, Product> locked = products.findAllByIdForUpdate(
                        order.getItems().stream().map(OrderItem::getProductId).toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return order.getItems().stream().collect(Collectors.toMap(Function.identity(),
                i -> locked.get(i.getProductId()), (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
