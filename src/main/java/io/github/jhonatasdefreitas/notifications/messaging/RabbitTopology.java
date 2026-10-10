package io.github.jhonatasdefreitas.notifications.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.RetryPolicy;

/**
 * One direct exchange, and every queue bound to it by its own name.
 *
 * <pre>
 * notifications.email                 work queue, one per channel
 * notifications.email.wait.5000       no consumer; after the TTL the broker moves the message back to the work queue
 * notifications.parking-lot           messages that ran out of attempts or that the consumer rejected
 * </pre>
 */
@Configuration
public class RabbitTopology {

    public static final String EXCHANGE = "notifications";
    public static final String EMAIL_QUEUE = "notifications.email";
    public static final String SMS_QUEUE = "notifications.sms";
    public static final String PUSH_QUEUE = "notifications.push";
    public static final String PARKING_LOT = "notifications.parking-lot";

    public static final String ATTEMPT_HEADER = "x-attempt";
    public static final String REDELIVERIES_HEADER = "x-redeliveries";

    public static String workQueue(Channel channel) {
        return switch (channel) {
            case EMAIL -> EMAIL_QUEUE;
            case SMS -> SMS_QUEUE;
            case PUSH -> PUSH_QUEUE;
        };
    }

    /**
     * The TTL is part of the name because RabbitMQ refuses to redeclare a queue with different
     * arguments: a new interval in the configuration gets a new queue instead of a startup error.
     */
    public static String waitQueue(Channel channel, Duration delay) {
        return waitQueue(workQueue(channel), delay);
    }

    public static String waitQueue(String workQueue, Duration delay) {
        return "%s.wait.%d".formatted(workQueue, delay.toMillis());
    }

    @Bean
    Declarables notificationQueues(RetryPolicy retryPolicy) {
        DirectExchange exchange = new DirectExchange(EXCHANGE);
        List<Queue> queues = new ArrayList<>();
        queues.add(QueueBuilder.durable(PARKING_LOT).build());

        Set<Duration> delays = new LinkedHashSet<>(retryPolicy.intervals());
        for (Channel channel : Channel.values()) {
            String workQueue = workQueue(channel);
            queues.add(QueueBuilder.durable(workQueue)
                    .deadLetterExchange(EXCHANGE)
                    .deadLetterRoutingKey(PARKING_LOT)
                    .build());
            // One queue per interval, with the TTL on the queue: with a TTL on each message, RabbitMQ
            // only expires the head of the queue and a short delay would wait behind a long one.
            for (Duration delay : delays) {
                queues.add(QueueBuilder.durable(waitQueue(channel, delay))
                        .ttl(Math.toIntExact(delay.toMillis()))
                        .deadLetterExchange(EXCHANGE)
                        .deadLetterRoutingKey(workQueue)
                        .build());
            }
        }

        List<Declarable> declarables = new ArrayList<>();
        declarables.add(exchange);
        for (Queue queue : queues) {
            Binding binding = BindingBuilder.bind(queue).to(exchange).withQueueName();
            declarables.add(queue);
            declarables.add(binding);
        }
        return new Declarables(declarables);
    }
}
