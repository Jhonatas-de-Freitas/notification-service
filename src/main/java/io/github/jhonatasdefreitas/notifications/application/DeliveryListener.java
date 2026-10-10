package io.github.jhonatasdefreitas.notifications.application;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import io.github.jhonatasdefreitas.notifications.domain.RetryPolicy;
import io.github.jhonatasdefreitas.notifications.messaging.QueuePublisher;
import io.github.jhonatasdefreitas.notifications.messaging.QueuePublisher.Outgoing;
import io.github.jhonatasdefreitas.notifications.messaging.RabbitTopology;

@Component
class DeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryListener.class);

    private final DeliveryService deliveries;
    private final QueuePublisher publisher;
    private final RetryPolicy retryPolicy;

    DeliveryListener(DeliveryService deliveries, QueuePublisher publisher, RetryPolicy retryPolicy) {
        this.deliveries = deliveries;
        this.publisher = publisher;
        this.retryPolicy = retryPolicy;
    }

    // Each annotation creates its own listener container, with its own consumers and threads.
    // That is what keeps a slow channel from holding up the other two.
    //
    // An exception that leaves this method makes the container reject the message without requeue,
    // and the broker dead-letters it to the parking lot.
    @RabbitListener(id = "email", queues = RabbitTopology.EMAIL_QUEUE)
    @RabbitListener(id = "sms", queues = RabbitTopology.SMS_QUEUE)
    @RabbitListener(id = "push", queues = RabbitTopology.PUSH_QUEUE)
    void onMessage(Message message) {
        UUID notificationId = notificationIdOf(message);
        int attempt = attemptOf(message);
        try {
            deliveries.deliver(notificationId, attempt);
        } catch (RuntimeException e) {
            // Not a provider failure: DeliveryService records those as attempts. This is the service
            // itself failing, most likely the database, and the transaction took the attempt back with
            // it. Parked now, the message would leave the notification in flight forever.
            redeliverLater(message, notificationId, attempt, e);
        }
    }

    /**
     * Sends the same attempt to wait in the broker, straight to RabbitMQ because the outbox needs
     * the database that has just failed. The waits are the ones of the provider retries and they
     * run out the same way. Only then the message is parked, with the notification as it was.
     */
    private void redeliverLater(Message message, UUID notificationId, int attempt, RuntimeException failure) {
        int redelivery = redeliveriesOf(message) + 1;
        Optional<Duration> delay = retryPolicy.delayAfter(redelivery);
        if (delay.isEmpty()) {
            log.error("Notification {} could not be processed on attempt {}, even after {} redeliveries. Parking "
                    + "the message, the notification stays as it is", notificationId, attempt, redelivery - 1);
            throw new AmqpRejectAndDontRequeueException("Out of redeliveries for notification " + notificationId,
                    failure);
        }
        String waitQueue = RabbitTopology.waitQueue(message.getMessageProperties().getConsumerQueue(), delay.get());
        boolean accepted = publisher
                .publish(List.of(new Outgoing(waitQueue, notificationId, attempt, redelivery)))
                .isEmpty();
        if (!accepted) {
            throw new AmqpRejectAndDontRequeueException("Queue " + waitQueue + " does not exist", failure);
        }
        log.warn("Notification {} could not be processed on attempt {}, redelivery {} of {} in {} ms: {}",
                notificationId, attempt, redelivery, retryPolicy.intervals().size(), delay.get().toMillis(),
                failure.toString());
    }

    private static UUID notificationIdOf(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            return UUID.fromString(body);
        } catch (IllegalArgumentException e) {
            throw new AmqpRejectAndDontRequeueException("The message body is not a notification id: " + body, e);
        }
    }

    private static int attemptOf(Message message) {
        if (message.getMessageProperties().getHeader(RabbitTopology.ATTEMPT_HEADER) instanceof Number attempt) {
            return attempt.intValue();
        }
        throw new AmqpRejectAndDontRequeueException(
                "The message has no number in the " + RabbitTopology.ATTEMPT_HEADER + " header");
    }

    private static int redeliveriesOf(Message message) {
        return message.getMessageProperties().getHeader(RabbitTopology.REDELIVERIES_HEADER) instanceof Number count
                ? count.intValue()
                : 0;
    }
}
