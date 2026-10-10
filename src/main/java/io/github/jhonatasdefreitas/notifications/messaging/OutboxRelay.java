package io.github.jhonatasdefreitas.notifications.messaging;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jhonatasdefreitas.notifications.persistence.OutboxMessage;
import io.github.jhonatasdefreitas.notifications.persistence.OutboxRepository;

/**
 * Moves outbox rows to RabbitMQ. A row is only deleted after the broker confirms the message,
 * and the delete commits after that, so a crash at any point leaves the row to be published
 * again. The price is a possible duplicate, which the consumer knows how to ignore.
 */
@Component
public class OutboxRelay {

    public static final String ATTEMPT_HEADER = "x-attempt";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;
    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transactions;

    OutboxRelay(OutboxRepository outbox, RabbitTemplate rabbit, TransactionTemplate transactions) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${notifications.outbox.poll-interval}")
    public void publishPending() {
        try {
            int published;
            do {
                published = transactions.execute(status -> publishBatch());
            } while (published == BATCH_SIZE);
        } catch (AmqpException e) {
            log.warn("Could not publish to RabbitMQ, the messages stay in the outbox: {}", e.getMessage());
        }
    }

    private int publishBatch() {
        List<OutboxMessage> batch = outbox.lockNextBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return 0;
        }
        rabbit.invoke(operations -> {
            for (OutboxMessage message : batch) {
                operations.send(RabbitTopology.EXCHANGE, message.getQueue(), toAmqp(message));
            }
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
        outbox.deleteAllInBatch(batch);
        return batch.size();
    }

    private static Message toAmqp(OutboxMessage message) {
        return MessageBuilder.withBody(message.getNotificationId().toString().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setHeader(ATTEMPT_HEADER, message.getAttempt())
                .build();
    }
}
