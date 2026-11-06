package dev.caiorocha.orderflow.messaging.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Lote de eventos pendentes, bloqueados com SKIP LOCKED: várias instâncias do relay podem rodar
     * ao mesmo tempo sem publicar o mesmo evento em paralelo.
     */
    @Query(value = """
            select * from outbox_event
            where published_at is null and publish_attempts < :maxAttempts
            order by created_at, id
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("batchSize") int batchSize, @Param("maxAttempts") int maxAttempts);

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(String aggregateType, String aggregateId);

    List<OutboxEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByPublishedAtIsNull();
}
