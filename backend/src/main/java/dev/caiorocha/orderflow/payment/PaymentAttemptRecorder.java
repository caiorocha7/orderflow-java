package dev.caiorocha.orderflow.payment;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grava cada tentativa do gateway em transação própria, que já está commitada quando o gateway lança a
 * falha temporária. Fica em um bean separado de propósito: se a exceção saísse do próprio método
 * REQUIRES_NEW, essa transação interna também seria desfeita e a tentativa se perderia.
 */
@Component
public class PaymentAttemptRecorder {

    private final PaymentAttemptRepository attempts;

    public PaymentAttemptRecorder(PaymentAttemptRepository attempts) {
        this.attempts = attempts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int record(Long orderId, String outcome, String reference) {
        int attempt = attempts.countByOrderId(orderId) + 1;
        attempts.save(new PaymentAttempt(orderId, attempt, outcome, reference));
        return attempt;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public int previousAttempts(Long orderId) {
        return attempts.countByOrderId(orderId);
    }
}
