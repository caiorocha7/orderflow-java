package dev.caiorocha.orderflow.messaging.outbox;

import dev.caiorocha.orderflow.messaging.KafkaTopics;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relay do Transactional Outbox: lê eventos pendentes, publica no Kafka e marca como publicados.
 *
 * <p>Garantia <b>at-least-once</b>: se o processo cair depois do envio e antes do commit, o evento é
 * reenviado. Por isso o consumidor é idempotente pelo {@code eventId}.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       @Value("${orderflow.outbox.batch-size:50}") int batchSize,
                       @Value("${orderflow.outbox.max-attempts:20}") int maxAttempts) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${orderflow.outbox.poll-interval-ms:500}")
    public void publishPending() {
        Integer published;
        do {
            published = tx.execute(status -> publishBatch());
        } while (published != null && published == batchSize);
    }

    /** Executa dentro de uma transação: o lock SKIP LOCKED vale até o commit. */
    int publishBatch() {
        List<OutboxEvent> batch = outbox.lockPendingBatch(batchSize, maxAttempts);
        int ok = 0;
        for (OutboxEvent event : batch) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(KafkaTopics.ORDER_EVENTS,
                        event.getAggregateId(), event.getPayload());
                record.headers().add("eventId", event.getId().toString().getBytes(StandardCharsets.UTF_8));
                record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
                kafka.send(record).get(10, TimeUnit.SECONDS);
                event.markPublished();
                ok++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.markFailed("interrompido");
                break;
            } catch (Exception e) {
                // Broker indisponível: o evento continua pendente e será tentado no próximo ciclo.
                event.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
                log.warn("Falha ao publicar evento {} ({}): {}", event.getId(), event.getEventType(), e.getMessage());
                break;
            }
        }
        if (ok > 0) {
            log.debug("Outbox: {} evento(s) publicados", ok);
        }
        return batch.size() == ok ? ok : -1;
    }
}
