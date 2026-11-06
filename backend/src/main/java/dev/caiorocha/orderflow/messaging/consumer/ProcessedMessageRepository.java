package dev.caiorocha.orderflow.messaging.consumer;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, ProcessedMessage.Key> {
}
