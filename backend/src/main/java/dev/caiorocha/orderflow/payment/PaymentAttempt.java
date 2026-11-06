package dev.caiorocha.orderflow.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "payment_attempt")
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private int attempt;

    @Column(nullable = false, length = 30)
    private String outcome;

    @Column(name = "simulated_reference", length = 40)
    private String simulatedReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(Long orderId, int attempt, String outcome, String simulatedReference) {
        this.orderId = orderId;
        this.attempt = attempt;
        this.outcome = outcome;
        this.simulatedReference = simulatedReference;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public int getAttempt() { return attempt; }
    public String getOutcome() { return outcome; }
    public String getSimulatedReference() { return simulatedReference; }
    public Instant getCreatedAt() { return createdAt; }
}
