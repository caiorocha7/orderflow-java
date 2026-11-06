package dev.caiorocha.orderflow.messaging.outbox;

import dev.caiorocha.orderflow.messaging.OrderEvent;
import dev.caiorocha.orderflow.ordering.application.OrderEventPublisher;
import dev.caiorocha.orderflow.ordering.application.OrderEventType;
import dev.caiorocha.orderflow.ordering.domain.Order;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Adapter do port {@link OrderEventPublisher}: grava o evento no outbox na transação do chamador. */
@Component
public class OutboxOrderEventPublisher implements OrderEventPublisher {

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public OutboxOrderEventPublisher(OutboxRepository outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(OrderEventType type, Order order, String detail) {
        UUID eventId = UUID.randomUUID();
        OrderEvent event = new OrderEvent(eventId, type.name(), Instant.now(), order.getId(), order.code(),
                order.getStatus().name(), order.getTotalAmount(), order.getPaymentScenario().name(), detail);
        outbox.save(new OutboxEvent(eventId, "ORDER", String.valueOf(order.getId()), type.name(),
                mapper.writeValueAsString(event)));
    }
}
