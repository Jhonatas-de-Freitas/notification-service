package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;

class ChannelIsolationIT extends IntegrationTest {

    @Autowired
    RabbitListenerEndpointRegistry listeners;

    @Test
    void aStoppedSmsChannelDoesNotHoldEmailOrPush() {
        String userId = newUserWithEveryChannelOn();
        Map<String, String> recipient = Map.of(
                "name", "Ana Souza", "email", newEmail(), "phone", "+5511999990002", "deviceToken", "device-abc");
        MessageListenerContainer smsConsumers = listeners.getListenerContainer("sms");
        String email;
        String sms;
        String push;

        smsConsumers.stop();
        try {
            JsonNode created = send(generic(userId, recipient, "EMAIL", "SMS", "PUSH")).get("notifications");
            email = created.get(0).get("id").asString();
            sms = created.get(1).get("id").asString();
            push = created.get(2).get("id").asString();

            awaitStatus(email, "SENT");
            awaitStatus(push, "SENT");
            // The SMS message is in its queue, with nobody to take it.
            awaitPublished(sms);
            assertThat(statusOf(sms)).isEqualTo("PENDING");
        } finally {
            smsConsumers.start();
        }

        assertThat(successes(awaitStatus(sms, "SENT"))).containsExactly(true);
    }
}
