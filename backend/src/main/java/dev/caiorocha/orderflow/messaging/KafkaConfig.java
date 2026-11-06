package dev.caiorocha.orderflow.messaging;

import dev.caiorocha.orderflow.messaging.consumer.PaymentEventsListener;
import dev.caiorocha.orderflow.shared.DomainException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic orderEventsTopic(@Value("${orderflow.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(KafkaTopics.ORDER_EVENTS).partitions(partitions).replicas(1).build();
    }

    /** A DLT tem o mesmo número de partições: a mensagem vai para a mesma partição de origem. */
    @Bean
    NewTopic orderEventsDltTopic(@Value("${orderflow.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(KafkaTopics.ORDER_EVENTS_DLT).partitions(partitions).replicas(1).build();
    }

    /**
     * Falhas temporárias: retentativa com backoff fixo. Ao esgotar, a mensagem é publicada na DLT.
     * Mensagens malformadas e violações de regra de negócio não são retentadas.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
                                         @Value("${orderflow.payments.retry.interval-ms:1000}") long intervalMs,
                                         @Value("${orderflow.payments.retry.max-retries:3}") long maxRetries) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(KafkaTopics.ORDER_EVENTS_DLT, record.partition()));
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(intervalMs, maxRetries));
        handler.addNotRetryableExceptions(PaymentEventsListener.InvalidEventException.class, DomainException.class);
        return handler;
    }
}
