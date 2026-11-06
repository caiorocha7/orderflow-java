package dev.caiorocha.orderflow.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.caiorocha.orderflow.shared.DomainException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderTest {

    private Order newOrder() {
        return new Order("Cliente", "Cliente@Example.com", PaymentScenario.APPROVE, null, "op@test");
    }

    @Test
    void calculaTotalNoServidorComArredondamentoDeCentavos() {
        Order order = newOrder();
        order.addItem(1L, "A", "Produto A", new BigDecimal("10.005"), 3);
        order.addItem(2L, "B", "Produto B", new BigDecimal("0.10"), 7);

        assertThat(order.getItems()).extracting(OrderItem::getLineTotal)
                .containsExactly(new BigDecimal("30.03"), new BigDecimal("0.70"));
        assertThat(order.getTotalAmount()).isEqualByComparingTo("30.73");
    }

    @Test
    void novoPedidoComecaPendenteComHistoricoInicialEEmailNormalizado() {
        Order order = newOrder();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getCustomerEmail()).isEqualTo("cliente@example.com");
        assertThat(order.getHistory()).singleElement()
                .satisfies(h -> assertThat(h.getToStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT));
    }

    @Test
    void rejeitaProdutoRepetidoEQuantidadeInvalida() {
        Order order = newOrder();
        order.addItem(1L, "A", "Produto A", BigDecimal.ONE, 1);
        assertThatThrownBy(() -> order.addItem(1L, "A", "Produto A", BigDecimal.ONE, 1))
                .isInstanceOf(DomainException.class).hasMessageContaining("mais de uma vez");
        assertThatThrownBy(() -> order.addItem(2L, "B", "Produto B", BigDecimal.ONE, 0))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void transicaoValidaRegistraHistorico() {
        Order order = newOrder();
        order.transitionTo(OrderStatus.PAID, "aprovado", "system");
        order.transitionTo(OrderStatus.CANCELLED, "cliente desistiu", "admin");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getHistory()).extracting(OrderStatusHistory::getToStatus)
                .containsExactly(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.CANCELLED);
    }

    @ParameterizedTest(name = "{0} -> {1} é inválida")
    @CsvSource({
            "PAYMENT_DECLINED, PAID",
            "PAYMENT_FAILED, PAID",
            "CANCELLED, PAID",
            "CANCELLED, PENDING_PAYMENT",
            "PAID, PAYMENT_DECLINED",
            "PAID, PENDING_PAYMENT"
    })
    void transicoesInvalidasSaoBloqueadas(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @Test
    void transicaoInvalidaNaoAlteraOPedido() {
        Order order = newOrder();
        order.transitionTo(OrderStatus.PAYMENT_DECLINED, "recusado", "system");

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.PAID, "tarde demais", "system"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("INVALID_TRANSITION");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_DECLINED);
        assertThat(order.getHistory()).hasSize(2);
    }

    @Test
    void estadosFinais() {
        assertThat(OrderStatus.PAYMENT_DECLINED.isFinal()).isTrue();
        assertThat(OrderStatus.PAYMENT_FAILED.isFinal()).isTrue();
        assertThat(OrderStatus.CANCELLED.isFinal()).isTrue();
        assertThat(OrderStatus.PAID.isFinal()).isFalse();
    }
}
