package dev.caiorocha.orderflow.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Envelope serializado em JSON no Kafka. O {@code eventId} é a chave de idempotência do consumidor;
 * a chave da mensagem é o id do pedido, o que mantém a ordem dos eventos de um mesmo pedido na partição.
 */
public record OrderEvent(UUID eventId, String eventType, Instant occurredAt, Long orderId, String orderCode,
                         String status, BigDecimal totalAmount, String paymentScenario, String detail) {
}
