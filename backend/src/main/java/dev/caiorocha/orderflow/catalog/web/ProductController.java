package dev.caiorocha.orderflow.catalog.web;

import dev.caiorocha.orderflow.catalog.application.ProductService;
import dev.caiorocha.orderflow.catalog.domain.ProductStatus;
import dev.caiorocha.orderflow.catalog.web.ProductDtos.AdjustStockRequest;
import dev.caiorocha.orderflow.catalog.web.ProductDtos.CreateProductRequest;
import dev.caiorocha.orderflow.catalog.web.ProductDtos.ProductResponse;
import dev.caiorocha.orderflow.catalog.web.ProductDtos.StockMovementResponse;
import dev.caiorocha.orderflow.catalog.web.ProductDtos.UpdateProductRequest;
import dev.caiorocha.orderflow.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ProductResponse> list(@RequestParam(required = false) String search,
                                              @RequestParam(required = false) ProductStatus status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by("name"));
        return PageResponse.of(service.search(search, status, pageable).map(ProductResponse::from));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return ProductResponse.from(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse create(@Valid @RequestBody CreateProductRequest req) {
        return ProductResponse.from(service.create(new ProductService.CreateProduct(req.sku(), req.name(),
                req.description(), req.price(), req.initialQuantity(), req.lowStockThreshold())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody UpdateProductRequest req) {
        return ProductResponse.from(service.update(id, new ProductService.UpdateProduct(req.name(), req.description(),
                req.price(), req.lowStockThreshold(), req.status(), req.version())));
    }

    @PostMapping("/{id}/stock-adjustments")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse adjustStock(@PathVariable Long id, @Valid @RequestBody AdjustStockRequest req) {
        return ProductResponse.from(service.adjustStock(id, req.availableQuantity(), req.reason().trim()));
    }

    @GetMapping("/{id}/stock-movements")
    public List<StockMovementResponse> movements(@PathVariable Long id) {
        return service.movements(id).stream().map(StockMovementResponse::from).toList();
    }
}
