package io.github.jhonatasdefreitas.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.domain.RetryPolicy;
import io.github.jhonatasdefreitas.notifications.messaging.DeliveryQueue;
import io.github.jhonatasdefreitas.notifications.persistence.NotificationRepository;
import io.github.jhonatasdefreitas.notifications.provider.ChannelSender;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final NotificationRepository notifications;
    private final Map<Channel, ChannelSender> senders = new EnumMap<>(Channel.class);
    private final RetryPolicy retryPolicy;
    private final DeliveryQueue deliveryQueue;
    private final MeterRegistry metrics;
    private final Clock clock;

    DeliveryService(NotificationRepository notifications, List<ChannelSender> senders, RetryPolicy retryPolicy,
            DeliveryQueue deliveryQueue, MeterRegistry metrics, Clock clock) {
        this.notifications = notifications;
        senders.forEach(sender -> this.senders.put(sender.channel(), sender));
        this.retryPolicy = retryPolicy;
        this.deliveryQueue = deliveryQueue;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Makes one attempt and records what happened. The row stays locked while the provider is
     * called, so a second copy of the same message waits here and then finds the attempt already
     * recorded. It costs a database connection for the length of the call, which the short
     * provider timeouts keep small.
     */
    @Transactional
    public void deliver(UUID notificationId, int attempt) {
        Notification notification = notifications.findByIdForUpdate(notificationId).orElse(null);
        if (notification == null) {
            log.warn("Notification {} does not exist, dropping the message", notificationId);
            return;
        }
        if (!notification.awaitsAttempt(attempt)) {
            log.info("Notification {} is {} with {} attempts, ignoring a repeated message for attempt {}",
                    notificationId, notification.getStatus(), notification.getAttempts().size(), attempt);
            count(notification, "duplicate");
            return;
        }

        try {
            senders.get(notification.getChannel()).send(notification, attempt);
        } catch (RuntimeException e) {
            recordFailure(notification, attempt, e);
            return;
        }
        Instant now = clock.instant();
        notification.recordSuccess(now);
        count(notification, "sent");
        // Counted from the scheduled time, so a notification scheduled for tomorrow does not look a day late.
        metrics.timer("notifications.delivery.time", "channel", notification.getChannel().name())
                .record(Duration.between(notification.dueAt(), now));
        log.info("Notification {} sent by {} on attempt {}", notificationId, notification.getChannel(), attempt);
    }

    private void recordFailure(Notification notification, int attempt, RuntimeException error) {
        Optional<Duration> delay = retryPolicy.delayAfter(attempt);
        notification.recordFailure(clock.instant(), error.getMessage(), delay.isPresent());
        if (delay.isPresent()) {
            deliveryQueue.retryAfter(notification, attempt + 1, delay.get());
            count(notification, "retry");
            log.warn("Notification {} failed on attempt {} of {}, trying again in {} ms: {}", notification.getId(),
                    attempt, retryPolicy.maxAttempts(), delay.get().toMillis(), error.getMessage());
        } else {
            deliveryQueue.park(notification);
            count(notification, "failed");
            log.error("Notification {} failed on attempt {} of {}, giving up: {}", notification.getId(), attempt,
                    retryPolicy.maxAttempts(), error.getMessage());
        }
    }

    private void count(Notification notification, String result) {
        metrics.counter("notifications.deliveries", "channel", notification.getChannel().name(), "result", result)
                .increment();
    }
}
