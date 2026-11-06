package dev.caiorocha.orderflow.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Trilha imutável de cada mudança de estoque (quem, quanto, por quê, qual pedido). */
@Entity
@Table(name = "stock_movement")
public class StockMovement {

    public enum Type { INITIAL, ADJUSTMENT, RESERVE, RELEASE, CONSUME, RETURN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false)
    private int quantity;

    @Column(length = 200)
    private String reason;

    @Column(name = "created_by", nullable = false, length = 160)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockMovement() {
    }

    public StockMovement(Long productId, Long orderId, Type type, int quantity, String reason, String createdBy) {
        this.productId = productId;
        this.orderId = orderId;
        this.type = type;
        this.quantity = quantity;
        this.reason = reason;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public Long getOrderId() { return orderId; }
    public Type getType() { return type; }
    public int getQuantity() { return quantity; }
    public String getReason() { return reason; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
