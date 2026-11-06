package dev.caiorocha.orderflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.caiorocha.orderflow.catalog.domain.Product;
import dev.caiorocha.orderflow.messaging.consumer.ProcessingLog;
import dev.caiorocha.orderflow.messaging.consumer.ProcessingLogRepository;
import dev.caiorocha.orderflow.messaging.outbox.OutboxEvent;
import dev.caiorocha.orderflow.messaging.outbox.OutboxRepository;
import dev.caiorocha.orderflow.ordering.application.OrderService;
import dev.caiorocha.orderflow.ordering.domain.Order;
import dev.caiorocha.orderflow.ordering.domain.OrderStatus;
import dev.caiorocha.orderflow.ordering.domain.PaymentScenario;
import dev.caiorocha.orderflow.ordering.infrastructure.OrderRepository;
import dev.caiorocha.orderflow.payment.PaymentAttemptRepository;
import dev.caiorocha.orderflow.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Fluxo assíncrono de ponta a ponta com Kafka real: pedido -> outbox -> relay -> tópico -> consumidor de
 * pagamento -> novo status, baixa ou liberação do estoque. Nenhum passo é chamado diretamente pelo teste.
 */
class PaymentFlowIT extends IntegrationTest {

    @Autowired
    OrderService orderService;
    @Autowired
    OrderRepository orders;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    ProcessingLogRepository logs;
    @Autowired
    PaymentAttemptRepository attempts;
    @Autowired
    KafkaTemplate<String, String> kafka;

    private Order place(Product p, int qty, PaymentScenario scenario) {
        return orderService.create(new OrderService.CreateOrder("Cliente", "c@test.dev", scenario,
                List.of(new OrderService.ItemRequest(p.getId(), qty)), null)).order();
    }

    private OrderStatus awaitStatus(Order order, OrderStatus expected) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> orders.findById(order.getId()).orElseThrow().getStatus() == expected);
        return expected;
    }

    private List<ProcessingLog.Outcome> outcomes(Order order) {
        return logs.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()).stream().map(ProcessingLog::getOutcome).toList();
    }

    @Test
    void pagamentoAprovadoConsomeAReservaEPublicaOrderPaid() {
        Product p = product("Teclado", "100.00", 10);
        Order order = place(p, 3, PaymentScenario.APPROVE);

        awaitStatus(order, OrderStatus.PAID);

        Product after = reload(p);
        assertThat(after.getAvailableQuantity()).isEqualTo(7);
        assertThat(after.getReservedQuantity()).isZero();
        assertThat(outcomes(order)).containsExactly(ProcessingLog.Outcome.PROCESSED);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(outbox.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("ORDER", order.getId().toString()))
                        .extracting(OutboxEvent::getEventType, e -> e.getPublishedAt() != null)
                        .containsExactly(org.assertj.core.groups.Tuple.tuple("ORDER_CREATED", true),
                                org.assertj.core.groups.Tuple.tuple("ORDER_PAID", true)));
    }

    @Test
    void pagamentoRecusadoLiberaAReserva() {
        Product p = product("Mouse", "50.00", 4);
        Order order = place(p, 4, PaymentScenario.DECLINE);

        awaitStatus(order, OrderStatus.PAYMENT_DECLINED);

        Product after = reload(p);
        assertThat(after.getAvailableQuantity()).isEqualTo(4);
        assertThat(after.getReservedQuantity()).isZero();
        assertThat(jdbc.queryForList("select type from stock_movement where order_id=? order by id", String.class,
                order.getId())).containsExactly("RESERVE", "RELEASE");
    }

    @Test
    void falhaTemporariaERetentadaComBackoffEAprovadaNaTerceiraTentativa() {
        Product p = product("Monitor", "900.00", 2);
        Order order = place(p, 1, PaymentScenario.TRANSIENT_FAILURE);

        awaitStatus(order, OrderStatus.PAID);

        assertThat(outcomes(order)).containsExactly(ProcessingLog.Outcome.RETRYING, ProcessingLog.Outcome.RETRYING,
                ProcessingLog.Outcome.PROCESSED);
        assertThat(attempts.findByOrderIdOrderByAttemptAsc(order.getId())).extracting(a -> a.getOutcome())
                .containsExactly("TRANSIENT_FAILURE", "TRANSIENT_FAILURE", "APPROVED");
        assertThat(reload(p).getReservedQuantity()).isZero();
        assertThat(reload(p).getAvailableQuantity()).isEqualTo(1);
    }

    @Test
    void falhaDefinitivaEsgotaRetentativasVaiParaDltECompensaLiberandoEstoque() {
        Product p = product("Dock", "400.00", 3);
        Order order = place(p, 2, PaymentScenario.GATEWAY_DOWN);

        awaitStatus(order, OrderStatus.PAYMENT_FAILED);

        // 1 tentativa original + 3 retentativas, depois a DLT
        assertThat(attempts.countByOrderId(order.getId())).isEqualTo(4);
        assertThat(outcomes(order)).containsSubsequence(ProcessingLog.Outcome.RETRYING, ProcessingLog.Outcome.RETRYING,
                ProcessingLog.Outcome.RETRYING, ProcessingLog.Outcome.RETRYING, ProcessingLog.Outcome.DEAD_LETTERED);
        Product after = reload(p);
        assertThat(after.getAvailableQuantity()).isEqualTo(3);
        assertThat(after.getReservedQuantity()).isZero();
    }

    @Test
    void mensagemDuplicadaNoKafkaNaoCobraNemBaixaEstoqueDuasVezes() throws Exception {
        Product p = product("SSD", "500.00", 10);
        Order order = place(p, 2, PaymentScenario.APPROVE);
        awaitStatus(order, OrderStatus.PAID);

        // Reenvia exatamente a mesma mensagem (mesmo eventId), como faria um relay que caiu antes do commit.
        OutboxEvent created = outbox.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("ORDER",
                order.getId().toString()).getFirst();
        for (int i = 0; i < 2; i++) {
            var record = new ProducerRecord<>(KafkaTopics.ORDER_EVENTS, order.getId().toString(), created.getPayload());
            record.headers().add("eventId", created.getId().toString().getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get(10, TimeUnit.SECONDS);
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(outcomes(order))
                .containsExactly(ProcessingLog.Outcome.PROCESSED, ProcessingLog.Outcome.DUPLICATE_IGNORED,
                        ProcessingLog.Outcome.DUPLICATE_IGNORED));
        assertThat(attempts.countByOrderId(order.getId())).isEqualTo(1);
        assertThat(reload(p).getAvailableQuantity()).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from stock_movement where order_id=? and type='CONSUME'",
                Integer.class, order.getId())).isEqualTo(1);
    }

    @Test
    void mensagemMalformadaVaiParaDltSemTravarOConsumidor() throws Exception {
        kafka.send(KafkaTopics.ORDER_EVENTS, "x", "{isto nao e json").get(10, TimeUnit.SECONDS);
        Product p = product("Cabo", "10.00", 5);
        Order order = place(p, 1, PaymentScenario.APPROVE);

        // O consumidor segue processando as mensagens seguintes normalmente.
        awaitStatus(order, OrderStatus.PAID);
    }

    @Test
    void cancelarPedidoPendenteLiberaReservaEPagamentoPosteriorNaoECobrado() {
        Product p = product("Headset", "200.00", 5);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin@test", "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        try {
            // Escolhe o cenário de indisponibilidade para o pedido continuar pendente enquanto é cancelado.
            Order order = place(p, 2, PaymentScenario.GATEWAY_DOWN);
            orderService.cancel(order.getId(), "Cliente desistiu");

            Product after = reload(p);
            assertThat(after.getAvailableQuantity()).isEqualTo(5);
            assertThat(after.getReservedQuantity()).isZero();
            // As retentativas e a DLT encontram o pedido cancelado: nada é cobrado nem compensado de novo.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(outcomes(order))
                    .contains(ProcessingLog.Outcome.SKIPPED));
            assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(reload(p).getAvailableQuantity()).isEqualTo(5);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void cancelarPedidoPagoDevolveItensAoEstoque() {
        Product p = product("Webcam", "300.00", 6);
        Order order = place(p, 2, PaymentScenario.APPROVE);
        awaitStatus(order, OrderStatus.PAID);
        assertThat(reload(p).getAvailableQuantity()).isEqualTo(4);

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin@test", "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        try {
            orderService.cancel(order.getId(), null);
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(reload(p).getAvailableQuantity()).isEqualTo(6);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForList("select type from stock_movement where order_id=? order by id", String.class,
                order.getId())).containsExactly("RESERVE", "CONSUME", "RETURN");
    }
}
