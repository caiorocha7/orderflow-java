package dev.caiorocha.orderflow.messaging;

public final class KafkaTopics {

    /** Eventos de negócio do pedido (chave = id do pedido). */
    public static final String ORDER_EVENTS = "orderflow.order-events";
    /** Dead Letter Topic: mensagens que esgotaram as retentativas ou falharam de forma definitiva. */
    public static final String ORDER_EVENTS_DLT = ORDER_EVENTS + ".DLT";

    public static final String PAYMENTS_CONSUMER = "orderflow-payments";
    public static final String PAYMENTS_DLT_CONSUMER = "orderflow-payments-dlt";

    private KafkaTopics() {
    }
}
