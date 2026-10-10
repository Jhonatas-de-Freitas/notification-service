package io.github.jhonatasdefreitas.notifications.messaging;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.persistence.OutboxMessage;
import io.github.jhonatasdefreitas.notifications.persistence.OutboxRepository;

/**
 * Where the rest of the service says what should happen to a notification next. Nothing here
 * talks to RabbitMQ: each call writes an outbox row inside the caller's transaction, so the
 * message exists if and only if the change that caused it was committed.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class DeliveryQueue {

    private final OutboxRepository outbox;
    private final Clock clock;

    DeliveryQueue(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    public void enqueue(Notification notification) {
        add(notification, RabbitTopology.workQueue(notification.getChannel()), 1);
    }

    public void retryAfter(Notification notification, int nextAttempt, Duration delay) {
        add(notification, RabbitTopology.waitQueue(notification.getChannel(), delay), nextAttempt);
    }

    public void park(Notification notification) {
        add(notification, RabbitTopology.PARKING_LOT, notification.getAttempts().size());
    }

    private void add(Notification notification, String queue, int attempt) {
        outbox.save(new OutboxMessage(notification.getId(), queue, attempt, clock.instant()));
    }
}
