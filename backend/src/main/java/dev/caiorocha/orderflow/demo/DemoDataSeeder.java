package dev.caiorocha.orderflow.demo;

import dev.caiorocha.orderflow.auth.AppUser;
import dev.caiorocha.orderflow.auth.AppUserRepository;
import dev.caiorocha.orderflow.catalog.application.ProductService;
import dev.caiorocha.orderflow.catalog.infrastructure.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dados de DEMONSTRAÇÃO, ativados por {@code orderflow.demo.enabled=true}. Os usuários usam as senhas
 * definidas nas variáveis de ambiente (ver .env.example), gravadas como hash BCrypt. Os produtos são
 * fictícios e marcados com o prefixo DEMO- no SKU. Não roda se já houver dados.
 */
@Component
@ConditionalOnProperty(name = "orderflow.demo.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final AppUserRepository users;
    private final ProductRepository products;
    private final ProductService productService;
    private final PasswordEncoder encoder;
    private final String adminPassword;
    private final String operatorPassword;

    public DemoDataSeeder(AppUserRepository users, ProductRepository products, ProductService productService,
                          PasswordEncoder encoder,
                          @Value("${orderflow.demo.admin-password}") String adminPassword,
                          @Value("${orderflow.demo.operator-password}") String operatorPassword) {
        this.users = users;
        this.products = products;
        this.productService = productService;
        this.encoder = encoder;
        this.adminPassword = adminPassword;
        this.operatorPassword = operatorPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedUser("admin@orderflow.local", "Admin Demo", adminPassword, AppUser.Role.ADMIN);
        seedUser("operator@orderflow.local", "Operador Demo", operatorPassword, AppUser.Role.OPERATOR);
        if (products.count() > 0) {
            return;
        }
        record Seed(String sku, String name, String description, String price, int qty, int threshold) {
        }
        List.of(
                new Seed("DEMO-KB-001", "Teclado mecânico", "Switch marrom, layout ABNT2", "389.90", 42, 10),
                new Seed("DEMO-MS-002", "Mouse sem fio", "Sensor óptico 4000 DPI", "149.90", 75, 15),
                new Seed("DEMO-MN-003", "Monitor 27\"", "IPS, 2560x1440", "1899.00", 12, 5),
                new Seed("DEMO-HS-004", "Headset USB", "Microfone com cancelamento de ruído", "279.00", 6, 8),
                new Seed("DEMO-DK-005", "Dock USB-C", "HDMI, ethernet e 3 USB", "459.00", 3, 5),
                new Seed("DEMO-CB-006", "Cabo HDMI 2.1", "2 metros", "59.90", 120, 20),
                new Seed("DEMO-WC-007", "Webcam Full HD", "1080p, foco automático", "329.00", 18, 5),
                new Seed("DEMO-SSD-008", "SSD NVMe 1 TB", "PCIe 4.0", "549.00", 25, 8)
        ).forEach(s -> productService.create(new ProductService.CreateProduct(s.sku(), s.name(), s.description(),
                new BigDecimal(s.price()), s.qty(), s.threshold())));
        log.info("Dados de demonstração criados (usuários e produtos DEMO-*)");
    }

    private void seedUser(String email, String name, String password, AppUser.Role role) {
        if (!users.existsByEmail(email)) {
            users.save(new AppUser(email, name, encoder.encode(password), role));
        }
    }
}
