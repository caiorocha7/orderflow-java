package dev.caiorocha.orderflow.ordering.application;

import dev.caiorocha.orderflow.ordering.domain.Order;

/**
 * Port de saída para eventos de negócio do pedido. A implementação grava no outbox dentro da
 * transação corrente, então o evento só existe se o pedido existir (e vice-versa).
 */
public interface OrderEventPublisher {

    void publish(OrderEventType type, Order order, String detail);
}
