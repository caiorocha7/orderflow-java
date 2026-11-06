package dev.caiorocha.orderflow.payment;

import dev.caiorocha.orderflow.ordering.domain.Order;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gateway SIMULADO. O resultado depende do {@code paymentScenario} escolhido no pedido, para que cada
 * caminho (aprovação, recusa, falha temporária e indisponibilidade) possa ser demonstrado e testado.
 *
 * <p>Cada tentativa é registrada antes de o resultado ser devolvido (ou de a falha ser lançada), então o
 * histórico mostra também as tentativas que falharam e foram retentadas.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private final PaymentAttemptRecorder recorder;
    private final int transientFailures;

    public SimulatedPaymentGateway(PaymentAttemptRecorder recorder,
                                   @Value("${orderflow.payments.simulated-transient-failures:2}") int transientFailures) {
        this.recorder = recorder;
        this.transientFailures = transientFailures;
    }

    @Override
    public PaymentResult charge(Order order) {
        int attempt = recorder.previousAttempts(order.getId()) + 1;
        return switch (order.getPaymentScenario()) {
            case APPROVE -> approve(order);
            case DECLINE -> {
                recorder.record(order.getId(), "DECLINED", null);
                yield PaymentResult.declined("Recusado pelo emissor simulado");
            }
            case TRANSIENT_FAILURE -> attempt <= transientFailures ? transientFailure(order) : approve(order);
            case GATEWAY_DOWN -> transientFailure(order);
        };
    }

    private PaymentResult approve(Order order) {
        String reference = "SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        recorder.record(order.getId(), "APPROVED", reference);
        return PaymentResult.approved(reference);
    }

    private PaymentResult transientFailure(Order order) {
        int attempt = recorder.record(order.getId(), "TRANSIENT_FAILURE", null);
        throw new TransientPaymentException("Gateway simulado indisponível (tentativa " + attempt + ")");
    }
}
