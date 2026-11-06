package dev.caiorocha.orderflow.messaging.consumer;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Marca que um consumidor já aplicou o efeito de um evento. A PK (consumer, event_id) impede duplicidade. */
@Entity
@Table(name = "processed_message")
public class ProcessedMessage {

    @EmbeddedId
    private Key id;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedMessage() {
    }

    public ProcessedMessage(String consumer, UUID eventId) {
        this.id = new Key(consumer, eventId);
        this.processedAt = Instant.now();
    }

    public Key getId() { return id; }
    public Instant getProcessedAt() { return processedAt; }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "consumer", length = 60)
        private String consumer;

        @Column(name = "event_id")
        private UUID eventId;

        protected Key() {
        }

        public Key(String consumer, UUID eventId) {
            this.consumer = consumer;
            this.eventId = eventId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && consumer.equals(k.consumer) && eventId.equals(k.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumer, eventId);
        }
    }
}
