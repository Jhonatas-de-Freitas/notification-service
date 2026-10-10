package io.github.jhonatasdefreitas.notifications.messaging;

import java.util.UUID;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import io.github.jhonatasdefreitas.notifications.application.DeliveryService;

@Component
class DeliveryListener {

    private final DeliveryService deliveries;

    DeliveryListener(DeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    // Each annotation creates its own listener container, with its own consumers and threads.
    // That is what keeps a slow channel from holding up the other two.
    //
    // A provider failure is not an exception here: DeliveryService records it and schedules the
    // retry. What does throw (a malformed message, the database down) makes the container reject
    // the message without requeue, and the broker dead-letters it to the parking lot.
    @RabbitListener(id = "email", queues = RabbitTopology.EMAIL_QUEUE)
    @RabbitListener(id = "sms", queues = RabbitTopology.SMS_QUEUE)
    @RabbitListener(id = "push", queues = RabbitTopology.PUSH_QUEUE)
    void onMessage(String notificationId, @Header(OutboxRelay.ATTEMPT_HEADER) int attempt) {
        deliveries.deliver(UUID.fromString(notificationId), attempt);
    }
}
