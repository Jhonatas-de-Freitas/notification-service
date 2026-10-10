package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.jhonatasdefreitas.notifications.messaging.RabbitTopology;

class DuplicateDeliveryIT extends IntegrationTest {

    @Autowired
    RabbitListenerEndpointRegistry listeners;

    @Test
    void aMessageRedeliveredAfterTheNotificationWasSentIsIgnored() {
        String email = newEmail();
        String id = sendOne(generic(newUser(), emailRecipient(email), "EMAIL"));
        awaitStatus(id, "SENT");
        double ignoredBefore = deliveries("EMAIL", "duplicate");

        publishFirstAttemptAgain(id);
        publishFirstAttemptAgain(id);

        await().atMost(PATIENCE).until(() -> deliveries("EMAIL", "duplicate") == ignoredBefore + 2);
        assertThat(successes(notification(id))).containsExactly(true);
        assertThat(mailbox.messagesTo(email)).hasSize(1);
    }

    @Test
    void twoCopiesOfTheSameMessageTakenTogetherSendOneEmail() {
        String email = newEmail();
        double ignoredBefore = deliveries("EMAIL", "duplicate");
        MessageListenerContainer emailConsumers = listeners.getListenerContainer("email");
        String id;

        // With the consumers stopped, both copies are in the queue when they come back,
        // and the two consumer threads pick one each.
        emailConsumers.stop();
        try {
            id = sendOne(generic(newUser(), emailRecipient(email), "EMAIL"));
            awaitPublished(id);
            publishFirstAttemptAgain(id);
        } finally {
            emailConsumers.start();
        }

        awaitStatus(id, "SENT");
        await().atMost(PATIENCE).until(() -> deliveries("EMAIL", "duplicate") == ignoredBefore + 1);
        assertThat(successes(notification(id))).containsExactly(true);
        assertThat(mailbox.messagesTo(email)).hasSize(1);
    }

    /** Returns after the broker confirms, so the copy is in the queue and not on its way. */
    private void publishFirstAttemptAgain(String notificationId) {
        rabbit.invoke(channel -> {
            channel.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.EMAIL_QUEUE, notificationId, message -> {
                message.getMessageProperties().setHeader(RabbitTopology.ATTEMPT_HEADER, 1);
                return message;
            });
            channel.waitForConfirmsOrDie(PATIENCE.toMillis());
            return null;
        });
    }
}
