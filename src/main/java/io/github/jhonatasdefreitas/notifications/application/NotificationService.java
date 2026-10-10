package io.github.jhonatasdefreitas.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.Content;
import io.github.jhonatasdefreitas.notifications.domain.MessageTemplates;
import io.github.jhonatasdefreitas.notifications.domain.NewNotification;
import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.domain.NotificationRequest;
import io.github.jhonatasdefreitas.notifications.domain.NotificationStatus;
import io.github.jhonatasdefreitas.notifications.domain.Preferences;
import io.github.jhonatasdefreitas.notifications.messaging.DeliveryQueue;
import io.github.jhonatasdefreitas.notifications.persistence.NotificationRepository;
import io.github.jhonatasdefreitas.notifications.persistence.NotificationRequestRepository;
import io.github.jhonatasdefreitas.notifications.persistence.PreferencesRepository;

@Service
public class NotificationService {

    public record Accepted(UUID requestId, List<Notification> notifications) {
    }

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRequestRepository requests;
    private final NotificationRepository notifications;
    private final PreferencesRepository preferences;
    private final DeliveryQueue deliveryQueue;
    private final TransactionTemplate transactions;
    private final Clock clock;

    NotificationService(NotificationRequestRepository requests, NotificationRepository notifications,
            PreferencesRepository preferences, DeliveryQueue deliveryQueue, TransactionTemplate transactions,
            Clock clock) {
        this.requests = requests;
        this.notifications = notifications;
        this.preferences = preferences;
        this.deliveryQueue = deliveryQueue;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Stores one notification per channel and queues the ones that can go out now. A repeated
     * idempotency key creates nothing: the caller gets what the first call created.
     */
    public Accepted accept(NewNotification request, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<Accepted> first = firstResult(idempotencyKey);
            if (first.isPresent()) {
                return first.get();
            }
        }
        Content content = MessageTemplates.compose(request.template(), request.orderId(), request.params());
        try {
            return transactions.execute(status -> create(request, content, idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            // Two calls with the same key arrived together and the unique index let only one in.
            return Optional.ofNullable(idempotencyKey).flatMap(this::firstResult).orElseThrow(() -> e);
        }
    }

    private Accepted create(NewNotification request, Content content, String idempotencyKey) {
        Instant now = clock.instant();
        Preferences userPreferences = preferences.findById(request.userId())
                .orElseGet(() -> Preferences.defaultsFor(request.userId()));
        NotificationRequest accepted = requests.saveAndFlush(new NotificationRequest(idempotencyKey, now));

        List<Notification> created = new ArrayList<>();
        for (Channel channel : channelsOf(request, userPreferences)) {
            Notification notification = notifications.save(new Notification(accepted.getId(), request, channel,
                    content.forChannel(channel, request.recipient().name()), userPreferences, now));
            // The outbox row goes in this same transaction: either both exist or neither does.
            if (notification.getStatus() == NotificationStatus.PENDING) {
                deliveryQueue.enqueue(notification);
            }
            log.info("Notification {} created for user {} by {}: {}", notification.getId(), request.userId(),
                    channel, notification.getStatus());
            created.add(notification);
        }
        return new Accepted(accepted.getId(), created);
    }

    private static Set<Channel> channelsOf(NewNotification request, Preferences userPreferences) {
        Set<Channel> channels = EnumSet.noneOf(Channel.class);
        if (request.channels() != null) {
            channels.addAll(request.channels());
        } else {
            for (Channel channel : Channel.values()) {
                if (userPreferences.allows(channel)) {
                    channels.add(channel);
                }
            }
        }
        return channels;
    }

    private Optional<Accepted> firstResult(String idempotencyKey) {
        return requests.findByIdempotencyKey(idempotencyKey).map(request -> {
            List<Notification> created = new ArrayList<>(notifications.findByRequestId(request.getId()));
            created.sort(Comparator.comparing(Notification::getChannel));
            return new Accepted(request.getId(), created);
        });
    }
}
