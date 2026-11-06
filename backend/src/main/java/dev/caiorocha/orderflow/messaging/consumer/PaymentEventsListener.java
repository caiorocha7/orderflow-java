package dev.caiorocha.orderflow.messaging.consumer;

import dev.caiorocha.orderflow.messaging.KafkaTopics;
import dev.caiorocha.orderflow.messaging.OrderEvent;
import dev.caiorocha.orderflow.messaging.consumer.ProcessingLog.Outcome;
import dev.caiorocha.orderflow.ordering.application.OrderEventType;
import dev.caiorocha.orderflow.payment.PaymentProcessingService;
import dev.caiorocha.orderflow.payment.TransientPaymentException;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumidores Kafka. O fluxo real de pagamento passa por aqui: não há chamada direta entre a criação do
 * pedido e o processamento do pagamento.
 */
@Component
public class PaymentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsListener.class);

    private final PaymentProcessingService payments;
    private final ProcessingLogWriter logWriter;
    private final ObjectMapper mapper;

    public PaymentEventsListener(PaymentProcessingService payments, ProcessingLogWriter logWriter, ObjectMapper mapper) {
        this.payments = payments;
        this.logWriter = logWriter;
        this.mapper = mapper;
    }

    @KafkaListener(id = KafkaTopics.PAYMENTS_CONSUMER, topics = KafkaTopics.ORDER_EVENTS,
            groupId = KafkaTopics.PAYMENTS_CONSUMER)
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        OrderEvent event = parse(record.value());
        if (!OrderEventType.ORDER_CREATED.name().equals(event.eventType())) {
            return; // este consumidor só reage à criação; os demais eventos são para outros interessados
        }
        int attempt = logWriter.previousRetries(event, KafkaTopics.PAYMENTS_CONSUMER) + 1;
        try {
            payments.processOrderCreated(event, attempt);
        } catch (TransientPaymentException e) {
            // Registra a tentativa em transação própria e relança: o DefaultErrorHandler aplica o backoff
            // e, ao esgotar as tentativas, publica na DLT.
            logWriter.recordIsolated(event, KafkaTopics.PAYMENTS_CONSUMER, Outcome.RETRYING, attempt, e.getMessage());
            log.warn("Falha temporária no pedido {} (tentativa {}): {}", event.orderId(), attempt, e.getMessage());
            throw e;
        }
    }

    @KafkaListener(id = KafkaTopics.PAYMENTS_DLT_CONSUMER, topics = KafkaTopics.ORDER_EVENTS_DLT,
            groupId = KafkaTopics.PAYMENTS_DLT_CONSUMER)
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        OrderEvent event;
        try {
            event = parse(record.value());
        } catch (InvalidEventException e) {
            log.error("Mensagem ilegível na DLT (offset {}); descartada após registro", record.offset());
            return;
        }
        Header reason = record.headers().lastHeader("kafka_dlt-exception-message");
        String why = reason == null ? "motivo não informado" : rootCause(new String(reason.value(), StandardCharsets.UTF_8));
        if (OrderEventType.ORDER_CREATED.name().equals(event.eventType())) {
            payments.processDeadLetter(event, why);
        }
    }

    /** O Spring Kafka prefixa a mensagem com a assinatura do método; guarda só a causa real. */
    static String rootCause(String dltMessage) {
        int idx = dltMessage.lastIndexOf("threw exception; ");
        return idx >= 0 ? dltMessage.substring(idx + "threw exception; ".length()) : dltMessage;
    }

    private OrderEvent parse(String json) {
        try {
            OrderEvent event = mapper.readValue(json, OrderEvent.class);
            if (event.eventId() == null || event.eventType() == null || event.orderId() == null) {
                throw new InvalidEventException("Evento sem campos obrigatórios");
            }
            return event;
        } catch (JacksonException e) {
            throw new InvalidEventException("JSON inválido: " + e.getOriginalMessage());
        }
    }

    /** Mensagem malformada: não adianta retentar, vai direto para a DLT. */
    public static class InvalidEventException extends DeserializationException {
        public InvalidEventException(String message) {
            super(message, null, false, null);
        }
    }
}
