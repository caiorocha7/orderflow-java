package dev.caiorocha.orderflow.messaging.consumer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processing_log")
public class ProcessingLog {

    public enum Outcome { PROCESSED, DUPLICATE_IGNORED, SKIPPED, RETRYING, DEAD_LETTERED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "order_id")
    private Long orderId;

    @Column(nullable = false, length = 60)
    private String consumer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Outcome outcome;

    @Column(nullable = false)
    private int attempt;

    @Column(length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProcessingLog() {
    }

    public ProcessingLog(UUID eventId, String eventType, Long orderId, String consumer, Outcome outcome, int attempt,
                         String detail) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.orderId = orderId;
        this.consumer = consumer;
        this.outcome = outcome;
        this.attempt = attempt;
        this.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 500));
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Long getOrderId() { return orderId; }
    public String getConsumer() { return consumer; }
    public Outcome getOutcome() { return outcome; }
    public int getAttempt() { return attempt; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
