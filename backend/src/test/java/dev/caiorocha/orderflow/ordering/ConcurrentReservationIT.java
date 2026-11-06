package dev.caiorocha.orderflow.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import dev.caiorocha.orderflow.catalog.domain.Product;
import dev.caiorocha.orderflow.ordering.application.OrderService;
import dev.caiorocha.orderflow.ordering.domain.PaymentScenario;
import dev.caiorocha.orderflow.shared.DomainException;
import dev.caiorocha.orderflow.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Concorrência real contra o PostgreSQL: várias threads criam pedidos ao mesmo tempo, cada uma em sua
 * própria transação e conexão. Sem o SELECT ... FOR UPDATE, o "verifica e depois reserva" venderia
 * mais do que o estoque.
 */
class ConcurrentReservationIT extends IntegrationTest {

    @Autowired
    OrderService orders;

    private String attempt(Product p, int qty) {
        try {
            orders.create(new OrderService.CreateOrder("Cliente", "c@test.dev", PaymentScenario.APPROVE,
                    List.of(new OrderService.ItemRequest(p.getId(), qty)), null));
            return "OK";
        } catch (DomainException e) {
            return e.code();
        }
    }

    private List<String> runConcurrently(int threads, Callable<String> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    @Test
    void vinteCompradoresDisputandoCincoUnidadesVendemExatamenteCinco() throws Exception {
        Product p = product("Edição limitada", "99.00", 5);

        List<String> results = runConcurrently(20, () -> attempt(p, 1));

        assertThat(results).filteredOn("OK"::equals).hasSize(5);
        assertThat(results).filteredOn("INSUFFICIENT_STOCK"::equals).hasSize(15);
        Product after = reload(p);
        assertThat(after.getAvailableQuantity()).isZero();
        assertThat(after.getReservedQuantity()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from customer_order", Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "select coalesce(sum(quantity),0) from stock_movement where type='RESERVE' and product_id=?",
                Integer.class, p.getId())).isEqualTo(5);
    }

    @Test
    void reservasConcorrentesDeQuantidadesDiferentesNuncaPassamDoEstoque() throws Exception {
        Product p = product("Lote", "10.00", 10);

        List<String> results = runConcurrently(12, () -> attempt(p, 3));

        long ok = results.stream().filter("OK"::equals).count();
        assertThat(ok).isEqualTo(3); // 3 x 3 = 9 de 10; o quarto pedido de 3 não cabe
        Product after = reload(p);
        assertThat(after.getAvailableQuantity()).isEqualTo(1);
        assertThat(after.getReservedQuantity()).isEqualTo(9);
    }

    @Test
    void pedidosComProdutosEmOrdemInversaNaoEntramEmDeadlock() throws Exception {
        Product a = product("A", "1.00", 100);
        Product b = product("B", "1.00", 100);

        List<String> results = runConcurrently(16, () -> {
            boolean reverse = Thread.currentThread().threadId() % 2 == 0;
            var items = reverse
                    ? List.of(new OrderService.ItemRequest(b.getId(), 1), new OrderService.ItemRequest(a.getId(), 1))
                    : List.of(new OrderService.ItemRequest(a.getId(), 1), new OrderService.ItemRequest(b.getId(), 1));
            orders.create(new OrderService.CreateOrder("C", "c@test.dev", PaymentScenario.APPROVE, items, null));
            return "OK";
        });

        assertThat(results).containsOnly("OK").hasSize(16);
        assertThat(reload(a).getAvailableQuantity()).isEqualTo(84);
        assertThat(reload(b).getAvailableQuantity()).isEqualTo(84);
    }
}
