package dev.caiorocha.orderflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.caiorocha.orderflow.ordering.domain.Order;
import dev.caiorocha.orderflow.ordering.domain.PaymentScenario;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifica o resultado de cada cenário e que toda tentativa fica registrada (inclusive as que falham). */
class SimulatedPaymentGatewayTest {

    /** Registro em memória no lugar do banco: guarda os resultados na ordem em que foram gravados. */
    private final List<String> outcomes = new ArrayList<>();
    private final PaymentAttemptRecorder recorder = new PaymentAttemptRecorder(null) {
        @Override
        public int record(Long orderId, String outcome, String reference) {
            outcomes.add(outcome);
            return outcomes.size();
        }

        @Override
        public int previousAttempts(Long orderId) {
            return outcomes.size();
        }
    };
    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(recorder, 2);

    private Order order(PaymentScenario scenario) {
        Order order = new Order("Cliente", "c@test.dev", scenario, null, "op");
        ReflectionTestUtils.setField(order, "id", 42L);
        return order;
    }

    @Test
    void aprovaComReferenciaSimulada() {
        var result = gateway.charge(order(PaymentScenario.APPROVE));
        assertThat(result.approved()).isTrue();
        assertThat(result.reference()).startsWith("SIM-");
        assertThat(outcomes).containsExactly("APPROVED");
    }

    @Test
    void recusaSemLancarExcecao() {
        var result = gateway.charge(order(PaymentScenario.DECLINE));
        assertThat(result.approved()).isFalse();
        assertThat(result.reason()).isNotBlank();
        assertThat(outcomes).containsExactly("DECLINED");
    }

    @Test
    void falhaTemporariaNasDuasPrimeirasTentativasEAprovaNaTerceira() {
        Order order = order(PaymentScenario.TRANSIENT_FAILURE);
        assertThatThrownBy(() -> gateway.charge(order)).isInstanceOf(TransientPaymentException.class)
                .hasMessageContaining("tentativa 1");
        assertThatThrownBy(() -> gateway.charge(order)).isInstanceOf(TransientPaymentException.class)
                .hasMessageContaining("tentativa 2");
        assertThat(gateway.charge(order).approved()).isTrue();
        assertThat(outcomes).containsExactly("TRANSIENT_FAILURE", "TRANSIENT_FAILURE", "APPROVED");
    }

    @Test
    void gatewayForaDoArSempreFalhaDeFormaTemporaria() {
        Order order = order(PaymentScenario.GATEWAY_DOWN);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> gateway.charge(order)).isInstanceOf(TransientPaymentException.class);
        }
        assertThat(outcomes).hasSize(5).containsOnly("TRANSIENT_FAILURE");
    }
}
