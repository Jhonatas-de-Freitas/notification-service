package io.github.jhonatasdefreitas.notifications.application;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.messaging.DeliveryQueue;
import io.github.jhonatasdefreitas.notifications.persistence.NotificationRepository;

/**
 * Queues the scheduled notifications whose time has come. Every instance of the service runs
 * this. The rows are taken with FOR UPDATE SKIP LOCKED and leave the SCHEDULED state in the same
 * transaction that writes the outbox row, so a notification is queued by one instance only.
 */
@Component
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);
    private static final int BATCH_SIZE = 100;

    private final NotificationRepository notifications;
    private final DeliveryQueue deliveryQueue;
    private final TransactionTemplate transactions;
    private final Clock clock;

    NotificationScheduler(NotificationRepository notifications, DeliveryQueue deliveryQueue,
            TransactionTemplate transactions, Clock clock) {
        this.notifications = notifications;
        this.deliveryQueue = deliveryQueue;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notifications.scheduler.poll-interval}")
    public void releaseDue() {
        int released;
        do {
            released = transactions.execute(status -> releaseBatch());
        } while (released == BATCH_SIZE);
    }

    private int releaseBatch() {
        List<Notification> due = notifications.lockDue(clock.instant(), BATCH_SIZE);
        for (Notification notification : due) {
            notification.release();
            deliveryQueue.enqueue(notification);
            log.info("Notification {} reached its scheduled time, queued for {}", notification.getId(),
                    notification.getChannel());
        }
        return due.size();
    }
}
