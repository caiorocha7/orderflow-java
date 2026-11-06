package dev.caiorocha.orderflow.ordering.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Máquina de estados do pedido. Toda mudança passa por {@link #canTransitionTo(OrderStatus)}.
 *
 * <pre>
 * PENDING_PAYMENT ─┬─> PAID ──> CANCELLED (estorno simulado + devolução ao estoque)
 *                  ├─> PAYMENT_DECLINED   (reserva liberada)
 *                  ├─> PAYMENT_FAILED     (falha definitiva após retentativas; reserva liberada)
 *                  └─> CANCELLED          (reserva liberada)
 * </pre>
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    PAYMENT_DECLINED,
    PAYMENT_FAILED,
    CANCELLED;

    private Set<OrderStatus> allowedNext() {
        return switch (this) {
            case PENDING_PAYMENT -> EnumSet.of(PAID, PAYMENT_DECLINED, PAYMENT_FAILED, CANCELLED);
            case PAID -> EnumSet.of(CANCELLED);
            case PAYMENT_DECLINED, PAYMENT_FAILED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isFinal() {
        return allowedNext().isEmpty();
    }
}
