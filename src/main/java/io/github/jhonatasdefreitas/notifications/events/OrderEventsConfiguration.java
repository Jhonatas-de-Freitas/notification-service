package io.github.jhonatasdefreitas.notifications.events;

import java.time.Duration;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import io.github.jhonatasdefreitas.notifications.application.NotificationService;

/**
 * The Kafka side of the service, off unless KAFKA_ENABLED is true. Without these beans nothing
 * connects to a broker, so the service runs on its own with PostgreSQL and RabbitMQ only.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "notifications.events.enabled", havingValue = "true")
@EnableConfigurationProperties(OrderEventsConfiguration.Retry.class)
class OrderEventsConfiguration {

    static final String TOPIC = "orders.events";
    static final String DEAD_LETTER = "notification-service.dlt";

    private static final Logger log = LoggerFactory.getLogger(OrderEventsConfiguration.class);

    @ConfigurationProperties("notifications.events.retry")
    record Retry(int maxAttempts, Duration initialInterval, double multiplier, Duration maxInterval) {
    }

    @Bean
    OrderEventListener orderEventListener(NotificationService notifications) {
        return new OrderEventListener(notifications);
    }

    // The dead letter topic belongs to this service, so it is created here when it does not exist.
    // The topic it listens to belongs to the order service.
    @Bean
    NewTopic deadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTER).partitions(3).replicas(1).build();
    }

    // Boot plugs the only CommonErrorHandler bean into the listener container.
    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafka, Retry retry) {
        // Negative partition: the producer picks it from the key, so the order keeps its partition there too.
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(DEAD_LETTER, -1));
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            deadLetters.accept(record, exception);
            // The container wraps what the listener threw. The cause is the part worth reading.
            Throwable failure = exception.getCause() != null ? exception.getCause() : exception;
            log.error("Message {}-{}@{} with key {} sent to {}: {}", record.topic(), record.partition(),
                    record.offset(), record.key(), DEAD_LETTER, failure.getMessage());
        };

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxAttempts() - 1);
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // Trying again cannot fix a message that is unreadable.
        errorHandler.addNotRetryableExceptions(InvalidEventException.class);
        return errorHandler;
    }
}
