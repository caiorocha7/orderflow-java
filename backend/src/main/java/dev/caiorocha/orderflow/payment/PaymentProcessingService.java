package dev.caiorocha.orderflow.payment;

import dev.caiorocha.orderflow.messaging.KafkaTopics;
import dev.caiorocha.orderflow.messaging.OrderEvent;
import dev.caiorocha.orderflow.messaging.consumer.ProcessedMessage;
import dev.caiorocha.orderflow.messaging.consumer.ProcessedMessageRepository;
import dev.caiorocha.orderflow.messaging.consumer.ProcessingLog.Outcome;
import dev.caiorocha.orderflow.messaging.consumer.ProcessingLogWriter;
import dev.caiorocha.orderflow.ordering.application.OrderService;
import dev.caiorocha.orderflow.ordering.domain.Order;
import dev.caiorocha.orderflow.ordering.domain.OrderStatus;
import dev.caiorocha.orderflow.ordering.infrastructure.OrderRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica o pagamento de um ORDER_CREATED de forma idempotente, em uma transação:
 * lock do pedido, checagem de "já processado", cobrança simulada, mudança de status, baixa ou liberação do
 * estoque, novo evento no outbox e marca de processado. Se algo falhar, tudo é desfeito junto.
 */
@Service
public class PaymentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessingService.class);
    private static final String CONSUMER = KafkaTopics.PAYMENTS_CONSUMER;
    private static final String DLT_CONSUMER = KafkaTopics.PAYMENTS_DLT_CONSUMER;

    private final OrderRepository orders;
    private final OrderService orderService;
    private final PaymentGateway gateway;
    private final ProcessedMessageRepository processed;
    private final ProcessingLogWriter logWriter;

    public PaymentProcessingService(OrderRepository orders, OrderService orderService, PaymentGateway gateway,
                                    ProcessedMessageRepository processed, ProcessingLogWriter logWriter) {
        this.orders = orders;
        this.orderService = orderService;
        this.gateway = gateway;
        this.processed = processed;
        this.logWriter = logWriter;
    }

    @Transactional
    public Outcome processOrderCreated(OrderEvent event, int attempt) {
        Optional<Order> found = orders.findByIdForUpdate(event.orderId());
        // Checagem DEPOIS do lock: duas entregas simultâneas do mesmo evento ficam serializadas aqui.
        if (processed.existsById(new ProcessedMessage.Key(CONSUMER, event.eventId()))) {
            logWriter.record(event, CONSUMER, Outcome.DUPLICATE_IGNORED, attempt, "Evento já processado; nenhum efeito aplicado");
            log.info("Evento duplicado ignorado: {} pedido {}", event.eventId(), event.orderId());
            return Outcome.DUPLICATE_IGNORED;
        }
        if (found.isEmpty() || found.get().getStatus() != OrderStatus.PENDING_PAYMENT) {
            String why = found.isEmpty() ? "Pedido inexistente" : "Pedido já está em " + found.get().getStatus();
            markProcessed(event, Outcome.SKIPPED, attempt, why + "; pagamento não cobrado");
            return Outcome.SKIPPED;
        }
        Order order = found.get();
        PaymentGateway.PaymentResult result = gateway.charge(order);
        if (result.approved()) {
            orderService.confirmPayment(order, result.reference());
            markProcessed(event, Outcome.PROCESSED, attempt, "Pagamento simulado aprovado: " + result.reference());
        } else {
            orderService.failPayment(order, OrderStatus.PAYMENT_DECLINED, "Pagamento simulado recusado: " + result.reason());
            markProcessed(event, Outcome.PROCESSED, attempt, "Pagamento simulado recusado; reserva liberada");
        }
        log.info("Pedido {} processado: {}", order.code(), order.getStatus());
        return Outcome.PROCESSED;
    }

    /** Compensação para mensagens que chegaram à DLT: falha definitiva, libera a reserva. */
    @Transactional
    public Outcome processDeadLetter(OrderEvent event, String failureReason) {
        Optional<Order> found = orders.findByIdForUpdate(event.orderId());
        if (processed.existsById(new ProcessedMessage.Key(DLT_CONSUMER, event.eventId()))) {
            logWriter.record(event, DLT_CONSUMER, Outcome.DUPLICATE_IGNORED, 1, "Mensagem da DLT já tratada");
            return Outcome.DUPLICATE_IGNORED;
        }
        processed.save(new ProcessedMessage(DLT_CONSUMER, event.eventId()));
        if (found.isPresent() && found.get().getStatus() == OrderStatus.PENDING_PAYMENT) {
            orderService.failPayment(found.get(), OrderStatus.PAYMENT_FAILED,
                    "Falha definitiva no pagamento simulado após retentativas");
            logWriter.record(event, DLT_CONSUMER, Outcome.DEAD_LETTERED, 1,
                    "Enviado à DLT (" + failureReason + "); pedido marcado como PAYMENT_FAILED e reserva liberada");
            return Outcome.DEAD_LETTERED;
        }
        logWriter.record(event, DLT_CONSUMER, Outcome.SKIPPED, 1,
                "Mensagem na DLT sem pedido pendente; nenhuma compensação necessária");
        return Outcome.SKIPPED;
    }

    private void markProcessed(OrderEvent event, Outcome outcome, int attempt, String detail) {
        processed.save(new ProcessedMessage(CONSUMER, event.eventId()));
        logWriter.record(event, CONSUMER, outcome, attempt, detail);
    }
}
