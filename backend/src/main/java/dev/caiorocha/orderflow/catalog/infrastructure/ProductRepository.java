package dev.caiorocha.orderflow.catalog.infrastructure;

import dev.caiorocha.orderflow.catalog.domain.Product;
import dev.caiorocha.orderflow.catalog.domain.ProductStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySku(String sku);

    /**
     * Bloqueia as linhas dos produtos (SELECT ... FOR UPDATE) sempre na mesma ordem (por id).
     * A ordem fixa evita deadlock entre pedidos que disputam os mesmos produtos em ordens diferentes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select p from Product p
            where (:status is null or p.status = :status)
              and (:search is null
                   or lower(p.name) like lower(concat('%', cast(:search as string), '%'))
                   or lower(p.sku) like lower(concat('%', cast(:search as string), '%')))
            """)
    Page<Product> search(@Param("search") String search, @Param("status") ProductStatus status, Pageable pageable);

    @Query("select p from Product p where p.status = 'ACTIVE' and p.availableQuantity <= p.lowStockThreshold order by p.availableQuantity, p.name")
    List<Product> findLowStock();

    long countByStatus(ProductStatus status);
}
