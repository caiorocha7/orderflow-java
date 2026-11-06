package dev.caiorocha.orderflow.messaging.web;

import dev.caiorocha.orderflow.messaging.consumer.ProcessingLog;
import dev.caiorocha.orderflow.messaging.consumer.ProcessingLogRepository;
import dev.caiorocha.orderflow.messaging.outbox.OutboxEvent;
import dev.caiorocha.orderflow.messaging.outbox.OutboxRepository;
import dev.caiorocha.orderflow.ordering.infrastructure.OrderRepository;
import dev.caiorocha.orderflow.payment.PaymentAttempt;
import dev.caiorocha.orderflow.payment.PaymentAttemptRepository;
import dev.caiorocha.orderflow.shared.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Visibilidade do processamento assíncrono: outbox, consumo no Kafka e tentativas de pagamento. */
@RestController
@Transactional(readOnly = true)
public class ProcessingController {

    private final OutboxRepository outbox;
    private final ProcessingLogRepository logs;
    private final PaymentAttemptRepository attempts;
    private final OrderRepository orders;

    public ProcessingController(OutboxRepository outbox, ProcessingLogRepository logs, PaymentAttemptRepository attempts,
                                OrderRepository orders) {
        this.outbox = outbox;
        this.logs = logs;
        this.attempts = attempts;
        this.orders = orders;
    }

    public record OutboxEventView(UUID id, String eventType, String aggregateId, Instant createdAt,
                                  Instant publishedAt, int publishAttempts, String lastError) {
        static OutboxEventView from(OutboxEvent e) {
            return new OutboxEventView(e.getId(), e.getEventType(), e.getAggregateId(), e.getCreatedAt(),
                    e.getPublishedAt(), e.getPublishAttempts(), e.getLastError());
        }
    }

    public record ProcessingLogView(Long id, UUID eventId, String eventType, Long orderId, String consumer,
                                    ProcessingLog.Outcome outcome, int attempt, String detail, Instant createdAt) {
        static ProcessingLogView from(ProcessingLog l) {
            return new ProcessingLogView(l.getId(), l.getEventId(), l.getEventType(), l.getOrderId(), l.getConsumer(),
                    l.getOutcome(), l.getAttempt(), l.getDetail(), l.getCreatedAt());
        }
    }

    public record PaymentAttemptView(int attempt, String outcome, String simulatedReference, Instant createdAt) {
        static PaymentAttemptView from(PaymentAttempt a) {
            return new PaymentAttemptView(a.getAttempt(), a.getOutcome(), a.getSimulatedReference(), a.getCreatedAt());
        }
    }

    public record OrderProcessing(List<OutboxEventView> events, List<ProcessingLogView> consumption,
                                  List<PaymentAttemptView> paymentAttempts) {
    }

    public record ProcessingOverview(long pendingOutbox, long processed, long duplicatesIgnored, long retries,
                                     long deadLettered, List<OutboxEventView> recentEvents,
                                     List<ProcessingLogView> recentConsumption) {
    }

    /** Linha do tempo assíncrona de um pedido (tela de detalhes). */
    @GetMapping("/api/orders/{id}/processing")
    public OrderProcessing forOrder(@PathVariable Long id) {
        if (!orders.existsById(id)) {
            throw new NotFoundException("Pedido", id);
        }
        return new OrderProcessing(
                outbox.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("ORDER", String.valueOf(id)).stream()
                        .map(OutboxEventView::from).toList(),
                logs.findByOrderIdOrderByCreatedAtAscIdAsc(id).stream().map(ProcessingLogView::from).toList(),
                attempts.findByOrderIdOrderByAttemptAsc(id).stream().map(PaymentAttemptView::from).toList());
    }

    /** Visão operacional global, restrita a administradores. */
    @GetMapping("/api/processing")
    @PreAuthorize("hasRole('ADMIN')")
    public ProcessingOverview overview(@RequestParam(defaultValue = "30") int limit) {
        var page = PageRequest.of(0, Math.clamp(limit, 1, 200));
        return new ProcessingOverview(
                outbox.countByPublishedAtIsNull(),
                logs.countByOutcome(ProcessingLog.Outcome.PROCESSED),
                logs.countByOutcome(ProcessingLog.Outcome.DUPLICATE_IGNORED),
                logs.countByOutcome(ProcessingLog.Outcome.RETRYING),
                logs.countByOutcome(ProcessingLog.Outcome.DEAD_LETTERED),
                outbox.findAllByOrderByCreatedAtDesc(page).stream().map(OutboxEventView::from).toList(),
                logs.findAllByOrderByCreatedAtDescIdDesc(page).stream().map(ProcessingLogView::from).toList());
    }
}
