package dev.caiorocha.orderflow.ordering.domain;

import dev.caiorocha.orderflow.shared.DomainException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Agregado Pedido: itens, total calculado no servidor e histórico de status. */
@Entity
@Table(name = "customer_order")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false, length = 120)
    private String customerName;

    @Column(name = "customer_email", nullable = false, length = 160)
    private String customerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_scenario", nullable = false, length = 30)
    private PaymentScenario paymentScenario;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "idempotency_key", unique = true, length = 80)
    private String idempotencyKey;

    @Column(name = "created_by", nullable = false, length = 160)
    private String createdBy;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("changedAt, id")
    private List<OrderStatusHistory> history = new ArrayList<>();

    protected Order() {
    }

    public Order(String customerName, String customerEmail, PaymentScenario paymentScenario, String idempotencyKey,
                 String createdBy) {
        this.customerName = customerName.trim();
        this.customerEmail = customerEmail.trim().toLowerCase();
        this.paymentScenario = paymentScenario;
        this.idempotencyKey = idempotencyKey;
        this.createdBy = createdBy;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.totalAmount = BigDecimal.ZERO.setScale(2);
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.history.add(new OrderStatusHistory(this, null, OrderStatus.PENDING_PAYMENT,
                "Pedido criado e estoque reservado", createdBy));
    }

    /** Adiciona um item com o preço vigente do produto e recalcula o total. */
    public void addItem(Long productId, String sku, String productName, BigDecimal unitPrice, int quantity) {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new DomainException("ORDER_LOCKED", "Itens só podem ser adicionados a pedidos novos");
        }
        if (quantity <= 0) {
            throw new DomainException("INVALID_QUANTITY", "A quantidade deve ser maior que zero");
        }
        if (items.stream().anyMatch(i -> i.getProductId().equals(productId))) {
            throw new DomainException("DUPLICATE_ITEM", "O produto " + sku + " aparece mais de uma vez no pedido");
        }
        items.add(new OrderItem(this, productId, sku, productName, unitPrice, quantity));
        totalAmount = items.stream().map(OrderItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Única porta de mudança de status: valida a transição e registra o histórico. */
    public void transitionTo(OrderStatus next, String reason, String changedBy) {
        if (!status.canTransitionTo(next)) {
            throw new DomainException("INVALID_TRANSITION",
                    "Não é possível mudar o pedido de " + status + " para " + next);
        }
        history.add(new OrderStatusHistory(this, status, next, reason, changedBy));
        status = next;
        updatedAt = Instant.now();
    }

    public String code() {
        return id == null ? null : "OF-%06d".formatted(id);
    }

    public Long getId() { return id; }
    public String getCustomerName() { return customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public OrderStatus getStatus() { return status; }
    public PaymentScenario getPaymentScenario() { return paymentScenario; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCreatedBy() { return createdBy; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }
    public List<OrderStatusHistory> getHistory() { return Collections.unmodifiableList(history); }
}
