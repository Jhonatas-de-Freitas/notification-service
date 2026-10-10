package io.github.jhonatasdefreitas.notifications.messaging;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.MessageProperties;
import com.rabbitmq.client.ReturnListener;

/**
 * Publishes to the queues and returns once the broker has answered for every message. A confirm
 * alone does not say that a message is in a queue: RabbitMQ also confirms the one that matched no
 * queue, right after dropping it. So every message goes out as mandatory, which makes the broker
 * hand that one back instead.
 */
@Component
public class QueuePublisher {

    public record Outgoing(String queue, UUID notificationId, int attempt, int redeliveries) {
    }

    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitTemplate rabbit;

    QueuePublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    /** Returns the positions, in the list, of the messages that no queue took. */
    public Set<Integer> publish(List<Outgoing> messages) {
        return rabbit.execute(channel -> {
            Set<String> handedBack = ConcurrentHashMap.newKeySet();
            ReturnListener returns = channel
                    .addReturnListener(returned -> handedBack.add(returned.getProperties().getMessageId()));
            try {
                for (int position = 0; position < messages.size(); position++) {
                    Outgoing message = messages.get(position);
                    channel.basicPublish(RabbitTopology.EXCHANGE, message.queue(), true,
                            properties(position, message),
                            message.notificationId().toString().getBytes(StandardCharsets.UTF_8));
                }
                // The broker sends the return of a message before its confirm, so after this nothing is missing.
                channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            } finally {
                // The channel goes back to a cache and would carry the listener to its next user.
                channel.removeReturnListener(returns);
            }
            Set<Integer> unroutable = new TreeSet<>();
            handedBack.forEach(position -> unroutable.add(Integer.valueOf(position)));
            return unroutable;
        });
    }

    // The message id is the position in the batch, which is how a returned message is matched to what was sent.
    private static AMQP.BasicProperties properties(int position, Outgoing message) {
        return MessageProperties.PERSISTENT_TEXT_PLAIN.builder()
                .messageId(String.valueOf(position))
                .contentEncoding(StandardCharsets.UTF_8.name())
                .headers(Map.of(
                        RabbitTopology.ATTEMPT_HEADER, message.attempt(),
                        RabbitTopology.REDELIVERIES_HEADER, message.redeliveries()))
                .build();
    }
}
