package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jhonatasdefreitas.notifications.application.DeliveryService;
import io.github.jhonatasdefreitas.notifications.messaging.RabbitTopology;
import io.github.jhonatasdefreitas.notifications.persistence.NotificationRepository;

class DuplicateDeliveryIT extends IntegrationTest {

    @Autowired
    RabbitListenerEndpointRegistry listeners;

    @Autowired
    DeliveryService deliveryService;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    TransactionTemplate transactions;

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
    void twoConsumersHoldingTheSameMessageAtTheSameTimeSendOneEmail() throws Exception {
        String email = newEmail();
        MessageListenerContainer emailConsumers = listeners.getListenerContainer("email");

        // The consumers of the application stay out of this one. Two threads of the test play the
        // two consumers, so both are sure to have the message in hand at the same moment.
        double sentBefore = deliveries("EMAIL", "sent");
        double ignoredBefore = deliveries("EMAIL", "duplicate");
        UUID id;
        emailConsumers.stop();
        try (ExecutorService consumers = Executors.newFixedThreadPool(2)) {
            id = UUID.fromString(sendOne(generic(newUser(), emailRecipient(email), "EMAIL")));
            Runnable delivery = () -> deliveryService.deliver(id, 1);

            List<Future<?>> bothDeliveries = transactions.execute(holdingTheRow -> {
                notifications.findByIdForUpdate(id).orElseThrow();
                List<Future<?>> started = List.of(consumers.submit(delivery), consumers.submit(delivery));
                // Neither can go on while the row is held here, so when it is let go
                // one of them takes it and the other one is already waiting behind.
                awaitSessionsBlockedByThisTransaction(2);
                return started;
            });
            for (Future<?> finished : bothDeliveries) {
                finished.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            }

            assertThat(deliveries("EMAIL", "sent")).isEqualTo(sentBefore + 1);
            assertThat(deliveries("EMAIL", "duplicate")).isEqualTo(ignoredBefore + 1);
        } finally {
            emailConsumers.start();
        }

        // The message the relay published was in the queue all along: a third copy, for the real consumers.
        await().atMost(PATIENCE).until(() -> deliveries("EMAIL", "duplicate") == ignoredBefore + 2);
        assertThat(deliveries("EMAIL", "sent")).isEqualTo(sentBefore + 1);
        assertThat(successes(notification(id.toString()))).containsExactly(true);
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
