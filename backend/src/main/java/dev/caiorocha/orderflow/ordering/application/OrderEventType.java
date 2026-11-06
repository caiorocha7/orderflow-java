package dev.caiorocha.orderflow.ordering.application;

/** Eventos de integração publicados no tópico de pedidos. */
public enum OrderEventType {
    ORDER_CREATED,
    ORDER_PAID,
    ORDER_PAYMENT_DECLINED,
    ORDER_PAYMENT_FAILED,
    ORDER_CANCELLED
}
