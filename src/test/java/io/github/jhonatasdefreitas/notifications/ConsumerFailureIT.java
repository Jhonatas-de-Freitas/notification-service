package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jhonatasdefreitas.notifications.persistence.NotificationRepository;
import tools.jackson.databind.JsonNode;

class ConsumerFailureIT extends IntegrationTest {

    @Autowired
    RabbitListenerEndpointRegistry listeners;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void aDeliveryThatLosesTheDatabaseHalfwayIsTriedAgainInsteadOfParked() {
        String email = newEmail();
        MessageListenerContainer emailConsumers = listeners.getListenerContainer("email");
        String id;

        emailConsumers.stop();
        try {
            id = sendOne(generic(newUser(), emailRecipient(email), "EMAIL"));
            awaitPublished(id);
            transactions.executeWithoutResult(holdingTheRow -> {
                notifications.findByIdForUpdate(UUID.fromString(id)).orElseThrow();
                emailConsumers.start();
                // The consumer is now stopped at the row held here. Closing its connection under it
                // is what a database restart looks like from the inside of a delivery.
                Integer consumer = awaitSessionsBlockedByThisTransaction(1).getFirst();
                jdbc.sql("select pg_terminate_backend(?)").param(consumer).query(Boolean.class).single();
            });
        } finally {
            emailConsumers.start();
        }

        // Had the message been parked, nothing would bring it back and this would stay PENDING.
        JsonNode sent = awaitStatus(id, "SENT");
        assertThat(successes(sent)).containsExactly(true);
        assertThat(mailbox.messagesTo(email)).hasSize(1);
    }
}
