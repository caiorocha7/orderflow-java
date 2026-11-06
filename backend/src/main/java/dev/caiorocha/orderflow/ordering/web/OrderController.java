package dev.caiorocha.orderflow.ordering.web;

import dev.caiorocha.orderflow.ordering.application.OrderService;
import dev.caiorocha.orderflow.ordering.domain.OrderStatus;
import dev.caiorocha.orderflow.ordering.infrastructure.OrderRepository;
import dev.caiorocha.orderflow.ordering.web.OrderDtos.CancelOrderRequest;
import dev.caiorocha.orderflow.ordering.web.OrderDtos.CreateOrderRequest;
import dev.caiorocha.orderflow.ordering.web.OrderDtos.OrderDetail;
import dev.caiorocha.orderflow.ordering.web.OrderDtos.OrderSummary;
import dev.caiorocha.orderflow.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@Validated
public class OrderController {

    private final OrderService service;
    private final OrderRepository orders;

    public OrderController(OrderService service, OrderRepository orders) {
        this.service = service;
        this.orders = orders;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> list(@RequestParam(required = false) OrderStatus status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageResponse.of(service.list(status, pageable).map(OrderSummary::from));
    }

    @GetMapping("/{id}")
    public OrderDetail get(@PathVariable Long id) {
        return OrderDetail.from(service.get(id));
    }

    /**
     * Cria o pedido. Com o header {@code Idempotency-Key}, reenvios da mesma requisição (duplo clique,
     * retry do cliente) devolvem o pedido já criado com status 200 em vez de criar outro.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<OrderDetail> create(
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 80) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest req) {
        var cmd = new OrderService.CreateOrder(req.customerName(), req.customerEmail(), req.paymentScenario(),
                req.items().stream().map(i -> new OrderService.ItemRequest(i.productId(), i.quantity())).toList(),
                idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim());
        OrderService.CreateResult result;
        try {
            result = service.create(cmd);
        } catch (DataIntegrityViolationException e) {
            // Duas requisições simultâneas com a mesma chave: a segunda perde no UNIQUE e devolve a primeira.
            if (cmd.idempotencyKey() == null) {
                throw e;
            }
            var existing = orders.findByIdempotencyKey(cmd.idempotencyKey()).orElseThrow(() -> e);
            return ResponseEntity.ok(OrderDetail.from(service.get(existing.getId())));
        }
        OrderDetail body = OrderDetail.from(service.get(result.order().getId()));
        return result.created()
                ? ResponseEntity.created(URI.create("/api/orders/" + body.id())).body(body)
                : ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public OrderDetail cancel(@PathVariable Long id, @Valid @RequestBody(required = false) CancelOrderRequest req) {
        service.cancel(id, req == null ? null : req.reason());
        return OrderDetail.from(service.get(id));
    }
}
