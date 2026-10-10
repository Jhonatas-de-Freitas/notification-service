package io.github.jhonatasdefreitas.notifications.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jhonatasdefreitas.notifications.messaging.QueuePublisher.Outgoing;
import io.github.jhonatasdefreitas.notifications.persistence.OutboxMessage;
import io.github.jhonatasdefreitas.notifications.persistence.OutboxRepository;

/**
 * Moves outbox rows to RabbitMQ. A row is only deleted after the broker has its message in a
 * queue, and the delete commits after that, so a crash at any point leaves the row to be published
 * again. The price is a possible duplicate, which the consumer knows how to ignore.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outbox;
    private final QueuePublisher publisher;
    private final AmqpAdmin admin;
    private final TransactionTemplate transactions;

    OutboxRelay(OutboxRepository outbox, QueuePublisher publisher, AmqpAdmin admin, TransactionTemplate transactions) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.admin = admin;
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
        Set<Integer> unroutable = publisher.publish(batch.stream()
                .map(row -> new Outgoing(row.getQueue(), row.getNotificationId(), row.getAttempt(), 0))
                .toList());

        List<OutboxMessage> published = new ArrayList<>();
        for (int position = 0; position < batch.size(); position++) {
            OutboxMessage row = batch.get(position);
            if (unroutable.contains(position)) {
                log.warn("Queue {} does not exist, the message of notification {} stays in the outbox",
                        row.getQueue(), row.getNotificationId());
            } else {
                published.add(row);
            }
        }
        outbox.deleteAllInBatch(published);

        if (!unroutable.isEmpty()) {
            // Queues are only declared when a connection opens. One deleted since then comes back here,
            // and the rows that stayed go out on the next round.
            admin.initialize();
        }
        return published.size();
    }
}
