package dev.caiorocha.orderflow.messaging.consumer;

import dev.caiorocha.orderflow.messaging.OrderEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProcessingLogWriter {

    private final ProcessingLogRepository logs;

    public ProcessingLogWriter(ProcessingLogRepository logs) {
        this.logs = logs;
    }

    /** Grava na transação corrente (junto com o efeito do processamento). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(OrderEvent event, String consumer, ProcessingLog.Outcome outcome, int attempt, String detail) {
        logs.save(new ProcessingLog(event.eventId(), event.eventType(), event.orderId(), consumer, outcome, attempt,
                detail));
    }

    /** Grava em transação própria: sobrevive ao rollback de uma tentativa que falhou. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIsolated(OrderEvent event, String consumer, ProcessingLog.Outcome outcome, int attempt,
                               String detail) {
        record(event, consumer, outcome, attempt, detail);
    }

    @Transactional(readOnly = true)
    public int previousRetries(OrderEvent event, String consumer) {
        return logs.countByEventIdAndConsumerAndOutcome(event.eventId(), consumer, ProcessingLog.Outcome.RETRYING);
    }
}
