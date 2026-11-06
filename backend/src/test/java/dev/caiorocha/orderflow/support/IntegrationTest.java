package dev.caiorocha.orderflow.support;

import dev.caiorocha.orderflow.TestcontainersConfiguration;
import dev.caiorocha.orderflow.catalog.application.ProductService;
import dev.caiorocha.orderflow.catalog.domain.Product;
import dev.caiorocha.orderflow.catalog.infrastructure.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base dos testes de integração: contexto Spring completo com PostgreSQL e Kafka reais (Testcontainers).
 * O contexto e os contêineres são compartilhados entre as classes (mesma configuração).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "orderflow.demo.enabled=false",
        "orderflow.outbox.poll-interval-ms=100",
        "orderflow.payments.retry.interval-ms=200",
        "orderflow.payments.retry.max-retries=3"
})
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ProductService productService;
    @Autowired
    protected ProductRepository productRepository;

    private static final List<String> TABLES_IN_DELETE_ORDER = List.of("processing_log", "processed_message",
            "payment_attempt", "outbox_event", "order_status_history", "order_item", "customer_order",
            "stock_movement", "product", "app_user");

    /**
     * Limpa os dados sem reiniciar as sequências (ids nunca se repetem entre testes). Usa DELETE em vez de
     * TRUNCATE porque consumidores Kafka de testes anteriores podem estar terminando em segundo plano, e o
     * lock exclusivo do TRUNCATE entraria em deadlock com eles. Em caso de conflito, tenta de novo.
     */
    @BeforeEach
    void cleanDatabase() {
        for (int attempt = 1; ; attempt++) {
            try {
                TABLES_IN_DELETE_ORDER.forEach(t -> jdbc.execute("delete from " + t));
                return;
            } catch (org.springframework.dao.DataAccessException e) {
                if (attempt == 5) {
                    throw e;
                }
            }
        }
    }

    protected Product product(String name, String price, int quantity) {
        return productService.create(new ProductService.CreateProduct(
                "T-" + UUID.randomUUID().toString().substring(0, 8), name, null, new BigDecimal(price), quantity, 2));
    }

    protected Product reload(Product p) {
        return productRepository.findById(p.getId()).orElseThrow();
    }
}
