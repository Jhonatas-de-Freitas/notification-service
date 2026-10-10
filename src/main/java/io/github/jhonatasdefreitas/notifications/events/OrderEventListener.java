package io.github.jhonatasdefreitas.notifications.events;

import java.util.Optional;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

import io.github.jhonatasdefreitas.notifications.application.NotificationService;
import io.github.jhonatasdefreitas.notifications.application.NotificationService.Accepted;
import io.github.jhonatasdefreitas.notifications.domain.NewNotification;

/**
 * Turns the order events into notifications. It is one more caller of the service, like the
 * REST API: from here on an event follows the same path as a POST.
 */
class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final NotificationService notifications;

    OrderEventListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    // The value arrives as plain text and is parsed here, so a message that is not JSON fails like any
    // other listener error and follows the same path to the dead letter topic.
    @KafkaListener(topics = OrderEventsConfiguration.TOPIC)
    void onMessage(ConsumerRecord<String, String> record) {
        OrderEvent event = OrderEvent.parse(record.value());
        Optional<NewNotification> notification = event.toNotification();
        if (notification.isEmpty()) {
            log.debug("Ignoring {} {}: nothing to tell the customer", event.type(), event.eventId());
            return;
        }
        Accepted accepted = notifications.accept(notification.get(), event.idempotencyKey());
        log.info("{} {} of order {} is request {} with {} notifications", event.type(), event.eventId(),
                event.orderId(), accepted.requestId(), accepted.notifications().size());
    }
}
