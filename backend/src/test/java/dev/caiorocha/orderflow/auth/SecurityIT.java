package dev.caiorocha.orderflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.caiorocha.orderflow.catalog.domain.Product;
import dev.caiorocha.orderflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

class SecurityIT extends IntegrationTest {

    @Autowired
    AppUserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void users() {
        users.save(new AppUser("admin@test.dev", "Admin", encoder.encode("admin-pass-123"), AppUser.Role.ADMIN));
        users.save(new AppUser("op@test.dev", "Operador", encoder.encode("op-pass-123"), AppUser.Role.OPERATOR));
    }

    /**
     * Login real (AuthenticationManager + BCrypt + sessão). O token CSRF vem do post-processor de teste;
     * o fluxo cookie XSRF-TOKEN -> header X-XSRF-TOKEN é coberto pelo E2E no navegador.
     */
    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void senhasSaoArmazenadasComoHashBcrypt() {
        String hash = users.findByEmail("admin@test.dev").orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("{bcrypt}$2").doesNotContain("admin-pass-123");
    }

    @Test
    void rotasProtegidasExigemAutenticacao() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void loginComSenhaErradaRetorna401ComMensagemGenerica() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.dev\",\"password\":\"errada\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("E-mail ou senha inválidos"));
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ninguem@test.dev\",\"password\":\"errada\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("E-mail ou senha inválidos"));
    }

    @Test
    void loginCriaSessaoComCookieHttpOnlyEMeRetornaOPapel() throws Exception {
        MockHttpSession session = login("op@test.dev", "op-pass-123");
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.email").value("op@test.dev"));
    }

    @Test
    void escritaSemTokenCsrfERejeitada() throws Exception {
        MockHttpSession session = login("admin@test.dev", "admin-pass-123");
        mvc.perform(post("/api/products").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"X1\",\"name\":\"X\",\"price\":1,\"initialQuantity\":1,\"lowStockThreshold\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutInvalidaASessao() throws Exception {
        MockHttpSession session = login("op@test.dev", "op-pass-123");
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void operadorNaoGerenciaProdutosNemEstoque() throws Exception {
        Product p = product("Teclado", "10.00", 5);
        mvc.perform(post("/api/products").with(user("op@test.dev").roles("OPERATOR")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"X1\",\"name\":\"X\",\"price\":1,\"initialQuantity\":1,\"lowStockThreshold\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/products/{id}/stock-adjustments", p.getId()).with(user("op@test.dev").roles("OPERATOR"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableQuantity\":999,\"reason\":\"tentativa\"}"))
                .andExpect(status().isForbidden());
        assertThat(reload(p).getAvailableQuantity()).isEqualTo(5);
    }

    @Test
    void historicoGlobalDeProcessamentoERestritoAoAdmin() throws Exception {
        mvc.perform(get("/api/processing").with(user("op@test.dev").roles("OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/processing").with(user("admin@test.dev").roles("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void operadorNaoCancelaPedidoDeOutraPessoaMasAdminSim() throws Exception {
        Product p = product("Mouse", "10.00", 5);
        String json = "{\"customerName\":\"Ana\",\"customerEmail\":\"ana@example.com\",\"paymentScenario\":\"GATEWAY_DOWN\","
                + "\"items\":[{\"productId\":" + p.getId() + ",\"quantity\":1}]}";
        String created = mvc.perform(post("/api/orders").with(user("outro@test.dev").roles("OPERATOR")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        mvc.perform(post("/api/orders/{id}/cancel", id).with(user("op@test.dev").roles("OPERATOR")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/{id}/cancel", id).with(user("admin@test.dev").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void adminCriaProdutoComSkuUnicoEControleDeVersao() throws Exception {
        String json = "{\"sku\":\"kb-99\",\"name\":\"Teclado\",\"price\":10.5,\"initialQuantity\":3,\"lowStockThreshold\":1}";
        String created = mvc.perform(post("/api/products").with(user("admin@test.dev").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("KB-99"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/products").with(user("admin@test.dev").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.replace("kb-99", "KB-99")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));

        int id = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        String stale = "{\"name\":\"Teclado 2\",\"price\":11,\"lowStockThreshold\":1,\"status\":\"ACTIVE\",\"version\":99}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/products/{id}", id)
                        .with(user("admin@test.dev").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
    }

    @Test
    void precoNegativoERejeitado() throws Exception {
        mvc.perform(post("/api/products").with(user("admin@test.dev").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"N1\",\"name\":\"X\",\"price\":-1,\"initialQuantity\":1,\"lowStockThreshold\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").exists());
    }

    @Test
    void loginSemTokenCsrfERejeitado() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.dev\",\"password\":\"admin-pass-123\"}"))
                .andExpect(status().isForbidden());
    }
}
