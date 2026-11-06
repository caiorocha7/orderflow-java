package dev.caiorocha.orderflow.catalog.application;

import dev.caiorocha.orderflow.catalog.domain.Product;
import dev.caiorocha.orderflow.catalog.domain.ProductStatus;
import dev.caiorocha.orderflow.catalog.domain.StockMovement;
import dev.caiorocha.orderflow.catalog.infrastructure.ProductRepository;
import dev.caiorocha.orderflow.catalog.infrastructure.StockMovementRepository;
import dev.caiorocha.orderflow.shared.ConflictException;
import dev.caiorocha.orderflow.shared.CurrentUser;
import dev.caiorocha.orderflow.shared.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository products;
    private final StockMovementRepository movements;

    public ProductService(ProductRepository products, StockMovementRepository movements) {
        this.products = products;
        this.movements = movements;
    }

    public record CreateProduct(String sku, String name, String description, BigDecimal price, int initialQuantity,
                                int lowStockThreshold) {
    }

    public record UpdateProduct(String name, String description, BigDecimal price, int lowStockThreshold,
                                ProductStatus status, long version) {
    }

    @Transactional
    public Product create(CreateProduct cmd) {
        String sku = Product.normalizeSku(cmd.sku());
        if (products.existsBySku(sku)) {
            throw new ConflictException("DUPLICATE_SKU", "Já existe um produto com o SKU " + sku);
        }
        Product product = products.saveAndFlush(new Product(sku, cmd.name(), cmd.description(), cmd.price(),
                cmd.initialQuantity(), cmd.lowStockThreshold()));
        if (cmd.initialQuantity() > 0) {
            movements.save(new StockMovement(product.getId(), null, StockMovement.Type.INITIAL, cmd.initialQuantity(),
                    "Estoque inicial", CurrentUser.username()));
        }
        return product;
    }

    /** Atualização com controle otimista: a versão enviada precisa ser a atual, senão 409. */
    @Transactional
    public Product update(Long id, UpdateProduct cmd) {
        Product product = get(id);
        if (product.getVersion() != cmd.version()) {
            throw new ConflictException("STALE_VERSION",
                    "O produto foi alterado por outra pessoa. Recarregue e tente novamente.");
        }
        product.updateDetails(cmd.name(), cmd.description(), cmd.price(), cmd.lowStockThreshold());
        if (cmd.status() != null && cmd.status() != product.getStatus()) {
            product.changeStatus(cmd.status());
        }
        return products.saveAndFlush(product);
    }

    /** Ajuste de inventário com bloqueio da linha, para não competir com reservas em andamento. */
    @Transactional
    public Product adjustStock(Long id, int newAvailable, String reason) {
        Product product = products.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Produto", id));
        int delta = product.adjustAvailableTo(newAvailable);
        if (delta != 0) {
            movements.save(new StockMovement(id, null, StockMovement.Type.ADJUSTMENT, delta, reason,
                    CurrentUser.username()));
        }
        return product;
    }

    @Transactional(readOnly = true)
    public Product get(Long id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("Produto", id));
    }

    @Transactional(readOnly = true)
    public Page<Product> search(String search, ProductStatus status, Pageable pageable) {
        String term = search == null || search.isBlank() ? null : search.trim();
        return products.search(term, status, pageable);
    }

    @Transactional(readOnly = true)
    public List<StockMovement> movements(Long productId) {
        get(productId);
        return movements.findTop50ByProductIdOrderByCreatedAtDescIdDesc(productId);
    }
}
