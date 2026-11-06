package dev.caiorocha.orderflow.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.caiorocha.orderflow.shared.DomainException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductTest {

    private Product product(int qty) {
        return new Product(" kb-01 ", "Teclado", null, new BigDecimal("100"), qty, 2);
    }

    @Test
    void normalizaSkuEPreco() {
        Product p = product(5);
        assertThat(p.getSku()).isEqualTo("KB-01");
        assertThat(p.getPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void reservaMoveDoDisponivelParaReservado() {
        Product p = product(5);
        p.reserve(3);
        assertThat(p.getAvailableQuantity()).isEqualTo(2);
        assertThat(p.getReservedQuantity()).isEqualTo(3);
    }

    @Test
    void reservaSemSaldoFalhaSemAlterarNada() {
        Product p = product(2);
        assertThatThrownBy(() -> p.reserve(3))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(p.getAvailableQuantity()).isEqualTo(2);
        assertThat(p.getReservedQuantity()).isZero();
    }

    @Test
    void liberarDevolveAoDisponivelEConsumirTiraDoEstoque() {
        Product p = product(10);
        p.reserve(4);
        p.releaseReservation(1);
        p.consumeReservation(3);
        assertThat(p.getAvailableQuantity()).isEqualTo(7);
        assertThat(p.getReservedQuantity()).isZero();
    }

    @Test
    void naoPermiteBaixarMaisDoQueOReservado() {
        Product p = product(10);
        p.reserve(1);
        assertThatThrownBy(() -> p.consumeReservation(2)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void produtoInativoNaoPodeSerReservado() {
        Product p = product(10);
        p.changeStatus(ProductStatus.INACTIVE);
        assertThatThrownBy(() -> p.reserve(1)).isInstanceOf(DomainException.class).hasMessageContaining("inativo");
    }

    @Test
    void rejeitaPrecoNegativoEAjusteNegativo() {
        assertThatThrownBy(() -> new Product("X", "X", null, new BigDecimal("-0.01"), 1, 0))
                .isInstanceOf(DomainException.class);
        Product p = product(1);
        assertThatThrownBy(() -> p.adjustAvailableTo(-1)).isInstanceOf(DomainException.class);
        assertThat(p.adjustAvailableTo(8)).isEqualTo(7);
    }

    @Test
    void estoqueBaixoQuandoDisponivelChegaAoLimite() {
        Product p = product(3);
        assertThat(p.isLowStock()).isFalse();
        p.reserve(1);
        assertThat(p.isLowStock()).isTrue();
    }
}
