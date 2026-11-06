package dev.caiorocha.orderflow.dashboard;

import dev.caiorocha.orderflow.catalog.domain.ProductStatus;
import dev.caiorocha.orderflow.catalog.infrastructure.ProductRepository;
import dev.caiorocha.orderflow.catalog.web.ProductDtos.ProductResponse;
import dev.caiorocha.orderflow.ordering.domain.OrderStatus;
import dev.caiorocha.orderflow.ordering.infrastructure.OrderRepository;
import dev.caiorocha.orderflow.ordering.web.OrderDtos.OrderSummary;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Indicadores calculados no banco a cada requisição (nenhum valor fixo no frontend). */
@RestController
public class DashboardController {

    private final OrderRepository orders;
    private final ProductRepository products;

    public DashboardController(OrderRepository orders, ProductRepository products) {
        this.orders = orders;
        this.products = products;
    }

    public record Dashboard(long totalOrders, long pendingOrders, long paidOrders, long declinedOrders,
                            long failedOrders, long cancelledOrders, long totalProducts, long activeProducts,
                            List<ProductResponse> lowStock, List<OrderSummary> recentOrders) {
    }

    @GetMapping("/api/dashboard")
    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        return new Dashboard(
                orders.count(),
                orders.countByStatus(OrderStatus.PENDING_PAYMENT),
                orders.countByStatus(OrderStatus.PAID),
                orders.countByStatus(OrderStatus.PAYMENT_DECLINED),
                orders.countByStatus(OrderStatus.PAYMENT_FAILED),
                orders.countByStatus(OrderStatus.CANCELLED),
                products.count(),
                products.countByStatus(ProductStatus.ACTIVE),
                products.findLowStock().stream().limit(10).map(ProductResponse::from).toList(),
                orders.findTop8ByOrderByCreatedAtDescIdDesc().stream().map(OrderSummary::from).toList());
    }
}
