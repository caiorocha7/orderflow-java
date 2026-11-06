package dev.caiorocha.orderflow.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentEventsListenerTest {

    @Test
    void motivoDaDltMostraSoACausaRealSemDetalhesInternos() {
        String raw = "Listener method 'public void dev.caiorocha.orderflow.messaging.consumer.PaymentEventsListener"
                + ".onOrderEvent(org.apache.kafka.clients.consumer.ConsumerRecord)' threw exception; "
                + "Gateway simulado indisponível (tentativa 4)";
        assertThat(PaymentEventsListener.rootCause(raw)).isEqualTo("Gateway simulado indisponível (tentativa 4)");
        assertThat(PaymentEventsListener.rootCause("timeout")).isEqualTo("timeout");
    }
}
