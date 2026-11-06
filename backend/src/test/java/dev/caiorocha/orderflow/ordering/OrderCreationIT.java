package dev.caiorocha.orderflow.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.caiorocha.orderflow.catalog.domain.Product;
import dev.caiorocha.orderflow.messaging.outbox.OutboxRepository;
import dev.caiorocha.orderflow.support.IntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class OrderCreationIT extends IntegrationTest {

    @Autowired
    OutboxRepository outbox;

    private MockHttpServletRequestBuilder createOrder(String json) {
        return post("/api/orders").with(user("op@test").roles("OPERATOR")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private String body(long productA, int qtyA, long productB, int qtyB) {
        return """
                {"customerName":"Maria Souza","customerEmail":"maria@example.com","paymentScenario":"APPROVE",
                 "items":[{"productId":%d,"quantity":%d},{"productId":%d,"quantity":%d}]}
                """.formatted(productA, qtyA, productB, qtyB);
    }

    @Test
    void criaPedidoValidoCalculaTotalReservaEstoqueEGravaEventoNoOutbox() throws Exception {
        Product a = product("Teclado", "389.90", 10);
        Product b = product("Mouse", "149.95", 5);

        mvc.perform(createOrder(body(a.getId(), 2, b.getId(), 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(1229.65))
                .andExpect(jsonPath("$.items[0].unitPrice").value(389.90))
                .andExpect(jsonPath("$.items[1].lineTotal").value(449.85))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("OF-")));

        assertThat(reload(a).getAvailableQuantity()).isEqualTo(8);
        assertThat(reload(a).getReservedQuantity()).isEqualTo(2);
        assertThat(reload(b).getAvailableQuantity()).isEqualTo(2);
        assertThat(outbox.findAll()).extracting(e -> e.getEventType()).contains("ORDER_CREATED");
    }

    @Test
    void ignoraValoresEnviadosPeloClienteEUsaOPrecoDoBanco() throws Exception {
        Product a = product("Monitor", "1000.00", 3);
        String json = """
                {"customerName":"Ana","customerEmail":"ana@example.com","totalAmount":0.01,
                 "items":[{"productId":%d,"quantity":1,"unitPrice":0.01}]}
                """.formatted(a.getId());

        mvc.perform(createOrder(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(1000.00))
                .andExpect(jsonPath("$.paymentScenario").value("APPROVE"));
    }

    @Test
    void precoDoItemFicaCongeladoMesmoSeOProdutoMudarDepois() throws Exception {
        Product a = product("Cabo", "50.00", 10);
        String location = mvc.perform(createOrder("""
                        {"customerName":"Ana","customerEmail":"ana@example.com","items":[{"productId":%d,"quantity":1}]}
                        """.formatted(a.getId())))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");

        Product current = reload(a);
        productService.update(a.getId(), new dev.caiorocha.orderflow.catalog.application.ProductService.UpdateProduct(
                "Cabo", null, new BigDecimal("80.00"), 2, null, current.getVersion()));

        mvc.perform(get(location).with(user("op@test").roles("OPERATOR")))
                .andExpect(jsonPath("$.items[0].unitPrice").value(50.00))
                .andExpect(jsonPath("$.totalAmount").value(50.00));
    }

    @Test
    void rejeitaDadosInvalidosComErrosPorCampo() throws Exception {
        mvc.perform(createOrder("""
                        {"customerName":"","customerEmail":"nao-e-email","items":[{"productId":1,"quantity":0}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.customerName").exists())
                .andExpect(jsonPath("$.errors.customerEmail").exists())
                .andExpect(jsonPath("$.errors['items[0].quantity']").exists());

        mvc.perform(createOrder("""
                        {"customerName":"Ana","customerEmail":"ana@example.com","items":[]}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void faltaDeEstoqueEmUmItemNaoReservaNenhumOutro() throws Exception {
        Product a = product("Teclado", "100.00", 10);
        Product b = product("Dock", "400.00", 1);
        long outboxBefore = outbox.count();

        mvc.perform(createOrder(body(a.getId(), 5, b.getId(), 2)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(reload(a).getAvailableQuantity()).isEqualTo(10);
        assertThat(reload(a).getReservedQuantity()).isZero();
        assertThat(reload(b).getAvailableQuantity()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from customer_order", Long.class)).isZero();
        assertThat(outbox.count()).isEqualTo(outboxBefore); // nenhum evento gravado: a transação foi desfeita
    }

    @Test
    void produtoInexistenteRetorna404() throws Exception {
        mvc.perform(createOrder("""
                        {"customerName":"Ana","customerEmail":"ana@example.com","items":[{"productId":999999,"quantity":1}]}
                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void mesmaIdempotencyKeyDevolveOMesmoPedidoSemReservarDeNovo() throws Exception {
        Product a = product("Webcam", "300.00", 5);
        String json = """
                {"customerName":"Ana","customerEmail":"ana@example.com","items":[{"productId":%d,"quantity":2}]}
                """.formatted(a.getId());

        String first = mvc.perform(createOrder(json).header("Idempotency-Key", "abc-123"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(createOrder(json).header("Idempotency-Key", "abc-123"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(second, "$.id"))
                .isEqualTo(com.jayway.jsonpath.JsonPath.<Integer>read(first, "$.id"));
        assertThat(reload(a).getReservedQuantity()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from customer_order", Long.class)).isEqualTo(1);
    }

    @Test
    void listaFiltraPorStatus() throws Exception {
        Product a = product("SSD", "500.00", 10);
        mvc.perform(createOrder("""
                {"customerName":"Ana","customerEmail":"ana@example.com","items":[{"productId":%d,"quantity":1}]}
                """.formatted(a.getId()))).andExpect(status().isCreated());

        mvc.perform(get("/api/orders").param("status", "CANCELLED").with(user("op@test").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/orders").with(user("op@test").roles("OPERATOR")))
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
