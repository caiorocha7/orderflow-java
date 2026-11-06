package dev.caiorocha.orderflow.messaging.consumer;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessingLogRepository extends JpaRepository<ProcessingLog, Long> {

    int countByEventIdAndConsumerAndOutcome(UUID eventId, String consumer, ProcessingLog.Outcome outcome);

    List<ProcessingLog> findByOrderIdOrderByCreatedAtAscIdAsc(Long orderId);

    List<ProcessingLog> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    long countByOutcome(ProcessingLog.Outcome outcome);
}
