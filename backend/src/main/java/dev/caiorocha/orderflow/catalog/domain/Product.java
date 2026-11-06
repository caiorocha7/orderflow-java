package dev.caiorocha.orderflow.catalog.domain;

import dev.caiorocha.orderflow.shared.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Produto e seu saldo de estoque.
 *
 * <ul>
 *   <li><b>available</b>: pode ser vendido agora.</li>
 *   <li><b>reserved</b>: separado para pedidos aguardando pagamento.</li>
 *   <li><b>consumido</b>: reserva confirmada por pagamento aprovado; sai do saldo (registrado em stock_movement).</li>
 *   <li><b>liberado</b>: reserva devolvida ao disponível (recusa, falha ou cancelamento).</li>
 * </ul>
 *
 * As operações de reserva assumem que a linha já está bloqueada (SELECT ... FOR UPDATE) pela transação chamadora.
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String sku;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "low_stock_threshold", nullable = false)
    private int lowStockThreshold;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Product() {
    }

    public Product(String sku, String name, String description, BigDecimal price, int initialQuantity,
                   int lowStockThreshold) {
        if (initialQuantity < 0) {
            throw new DomainException("INVALID_QUANTITY", "A quantidade inicial não pode ser negativa");
        }
        this.sku = normalizeSku(sku);
        this.status = ProductStatus.ACTIVE;
        this.availableQuantity = initialQuantity;
        this.reservedQuantity = 0;
        this.createdAt = Instant.now();
        updateDetails(name, description, price, lowStockThreshold);
    }

    public static String normalizeSku(String sku) {
        return sku == null ? null : sku.trim().toUpperCase();
    }

    public void updateDetails(String name, String description, BigDecimal price, int lowStockThreshold) {
        if (price == null || price.signum() < 0) {
            throw new DomainException("INVALID_PRICE", "O preço não pode ser negativo");
        }
        if (lowStockThreshold < 0) {
            throw new DomainException("INVALID_THRESHOLD", "O limite de estoque baixo não pode ser negativo");
        }
        this.name = name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.price = price.setScale(2, RoundingMode.HALF_UP);
        this.lowStockThreshold = lowStockThreshold;
        this.updatedAt = Instant.now();
    }

    public void changeStatus(ProductStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    /** Reserva para um pedido. Falha sem alterar nada se não houver saldo. */
    public void reserve(int quantity) {
        requirePositive(quantity);
        if (status != ProductStatus.ACTIVE) {
            throw new DomainException("PRODUCT_INACTIVE", "O produto " + sku + " está inativo");
        }
        if (availableQuantity < quantity) {
            throw new DomainException("INSUFFICIENT_STOCK",
                    "Estoque insuficiente para " + sku + ": disponível " + availableQuantity + ", solicitado " + quantity);
        }
        availableQuantity -= quantity;
        reservedQuantity += quantity;
        touch();
    }

    /** Devolve uma reserva ao disponível (pagamento recusado, falha definitiva ou cancelamento). */
    public void releaseReservation(int quantity) {
        requirePositive(quantity);
        requireReserved(quantity);
        reservedQuantity -= quantity;
        availableQuantity += quantity;
        touch();
    }

    /** Confirma a reserva como venda (pagamento aprovado): o item sai do estoque. */
    public void consumeReservation(int quantity) {
        requirePositive(quantity);
        requireReserved(quantity);
        reservedQuantity -= quantity;
        touch();
    }

    /** Devolve ao estoque itens de um pedido pago que foi cancelado. */
    public void restock(int quantity) {
        requirePositive(quantity);
        availableQuantity += quantity;
        touch();
    }

    /** Ajuste manual do disponível (inventário). Retorna a diferença aplicada. */
    public int adjustAvailableTo(int newAvailable) {
        if (newAvailable < 0) {
            throw new DomainException("INVALID_QUANTITY", "O estoque disponível não pode ser negativo");
        }
        int delta = newAvailable - availableQuantity;
        availableQuantity = newAvailable;
        touch();
        return delta;
    }

    public boolean isLowStock() {
        return availableQuantity <= lowStockThreshold;
    }

    private void requireReserved(int quantity) {
        if (reservedQuantity < quantity) {
            throw new IllegalStateException("Reserva inconsistente para " + sku + ": reservado " + reservedQuantity
                    + ", tentativa de baixar " + quantity);
        }
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new DomainException("INVALID_QUANTITY", "A quantidade deve ser maior que zero");
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getPrice() { return price; }
    public ProductStatus getStatus() { return status; }
    public int getAvailableQuantity() { return availableQuantity; }
    public int getReservedQuantity() { return reservedQuantity; }
    public int getLowStockThreshold() { return lowStockThreshold; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
