package dev.caiorocha.orderflow.catalog.infrastructure;

import dev.caiorocha.orderflow.catalog.domain.StockMovement;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    List<StockMovement> findTop50ByProductIdOrderByCreatedAtDescIdDesc(Long productId);

    List<StockMovement> findByOrderIdOrderByIdAsc(Long orderId);
}
