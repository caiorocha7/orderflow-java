package dev.caiorocha.orderflow.catalog.web;

import dev.caiorocha.orderflow.catalog.domain.Product;
import dev.caiorocha.orderflow.catalog.domain.ProductStatus;
import dev.caiorocha.orderflow.catalog.domain.StockMovement;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record CreateProductRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "use letras, números, ponto, hífen ou sublinhado") String sku,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull @DecimalMin(value = "0.00", message = "não pode ser negativo") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @NotNull @Min(0) @Max(1_000_000) Integer initialQuantity,
            @NotNull @Min(0) @Max(100_000) Integer lowStockThreshold) {
    }

    public record UpdateProductRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull @DecimalMin(value = "0.00", message = "não pode ser negativo") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @NotNull @Min(0) @Max(100_000) Integer lowStockThreshold,
            @NotNull ProductStatus status,
            @NotNull Long version) {
    }

    public record AdjustStockRequest(
            @NotNull @Min(0) @Max(1_000_000) Integer availableQuantity,
            @NotBlank @Size(max = 200) String reason) {
    }

    public record ProductResponse(Long id, String sku, String name, String description, BigDecimal price,
                                  ProductStatus status, int availableQuantity, int reservedQuantity,
                                  int lowStockThreshold, boolean lowStock, long version, Instant createdAt,
                                  Instant updatedAt) {

        public static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(),
                    p.getStatus(), p.getAvailableQuantity(), p.getReservedQuantity(), p.getLowStockThreshold(),
                    p.isLowStock(), p.getVersion(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record StockMovementResponse(Long id, StockMovement.Type type, int quantity, Long orderId, String reason,
                                        String createdBy, Instant createdAt) {

        public static StockMovementResponse from(StockMovement m) {
            return new StockMovementResponse(m.getId(), m.getType(), m.getQuantity(), m.getOrderId(), m.getReason(),
                    m.getCreatedBy(), m.getCreatedAt());
        }
    }
}
