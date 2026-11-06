package dev.caiorocha.orderflow.ordering.web;

import dev.caiorocha.orderflow.ordering.domain.Order;
import dev.caiorocha.orderflow.ordering.domain.OrderItem;
import dev.caiorocha.orderflow.ordering.domain.OrderStatus;
import dev.caiorocha.orderflow.ordering.domain.OrderStatusHistory;
import dev.caiorocha.orderflow.ordering.domain.PaymentScenario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    /** Não há campo de preço ou total: os valores são sempre calculados no backend. */
    public record CreateOrderRequest(
            @NotBlank @Size(max = 120) String customerName,
            @NotBlank @Email @Size(max = 160) String customerEmail,
            PaymentScenario paymentScenario,
            @NotEmpty @Size(max = 50) List<@Valid ItemRequest> items) {
    }

    public record ItemRequest(@NotNull Long productId, @NotNull @Min(1) @Max(10_000) Integer quantity) {
    }

    public record CancelOrderRequest(@Size(max = 200) String reason) {
    }

    public record OrderSummary(Long id, String code, String customerName, OrderStatus status,
                               PaymentScenario paymentScenario, BigDecimal totalAmount, int itemCount,
                               String createdBy, Instant createdAt) {

        public static OrderSummary from(Order o) {
            return new OrderSummary(o.getId(), o.code(), o.getCustomerName(), o.getStatus(), o.getPaymentScenario(),
                    o.getTotalAmount(), o.getItems().size(), o.getCreatedBy(), o.getCreatedAt());
        }
    }

    public record OrderItemResponse(Long productId, String sku, String productName, BigDecimal unitPrice, int quantity,
                                    BigDecimal lineTotal) {

        static OrderItemResponse from(OrderItem i) {
            return new OrderItemResponse(i.getProductId(), i.getSku(), i.getProductName(), i.getUnitPrice(),
                    i.getQuantity(), i.getLineTotal());
        }
    }

    public record StatusChange(OrderStatus fromStatus, OrderStatus toStatus, String reason, String changedBy,
                               Instant changedAt) {

        static StatusChange from(OrderStatusHistory h) {
            return new StatusChange(h.getFromStatus(), h.getToStatus(), h.getReason(), h.getChangedBy(),
                    h.getChangedAt());
        }
    }

    public record OrderDetail(Long id, String code, String customerName, String customerEmail, OrderStatus status,
                              PaymentScenario paymentScenario, BigDecimal totalAmount, String createdBy,
                              Instant createdAt, Instant updatedAt, boolean cancellable,
                              List<OrderItemResponse> items, List<StatusChange> history) {

        public static OrderDetail from(Order o) {
            return new OrderDetail(o.getId(), o.code(), o.getCustomerName(), o.getCustomerEmail(), o.getStatus(),
                    o.getPaymentScenario(), o.getTotalAmount(), o.getCreatedBy(), o.getCreatedAt(), o.getUpdatedAt(),
                    o.getStatus().canTransitionTo(OrderStatus.CANCELLED),
                    o.getItems().stream().map(OrderItemResponse::from).toList(),
                    o.getHistory().stream().map(StatusChange::from).toList());
        }
    }
}
