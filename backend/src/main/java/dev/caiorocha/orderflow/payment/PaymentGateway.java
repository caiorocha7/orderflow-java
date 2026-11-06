package dev.caiorocha.orderflow.payment;

import dev.caiorocha.orderflow.ordering.domain.Order;

/**
 * Port do gateway de pagamento. A única implementação é {@link SimulatedPaymentGateway}: não há
 * integração com meio de pagamento real nem tratamento de dados de cartão.
 *
 * @throws TransientPaymentException falha temporária (será retentada pelo consumidor Kafka)
 */
public interface PaymentGateway {

    PaymentResult charge(Order order);

    record PaymentResult(boolean approved, String reference, String reason) {

        public static PaymentResult approved(String reference) {
            return new PaymentResult(true, reference, null);
        }

        public static PaymentResult declined(String reason) {
            return new PaymentResult(false, null, reason);
        }
    }
}
