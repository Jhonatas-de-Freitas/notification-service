package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

import io.github.jhonatasdefreitas.notifications.messaging.OutboxRelay;
import io.github.jhonatasdefreitas.notifications.messaging.RabbitTopology;
import tools.jackson.databind.JsonNode;

class RetryIT extends IntegrationTest {

    @Test
    void anSmsThatFailsTwiceIsRetriedAndSentOnTheThirdAttempt() {
        String id = sendOne(generic(newUserWithEveryChannelOn(), smsRecipient("+5511999990001"), "SMS"));

        awaitStatus(id, "RETRYING");
        JsonNode sent = awaitStatus(id, "SENT");

        assertThat(successes(sent)).containsExactly(false, false, true);
        assertThat(sent.get("attempts").get(0).get("number").asInt()).isEqualTo(1);
        assertThat(sent.get("attempts").get(0).get("error").asString()).contains("simulated failure");
        assertThat(sent.get("attempts").get(2).get("error").isNull()).isTrue();
        assertThat(sent.get("sentAt").asString()).isEqualTo(sent.get("attempts").get(2).get("at").asString());
    }

    @Test
    void eachRetryWaitsAtLeastItsIntervalInTheBroker() {
        String id = sendOne(generic(newUserWithEveryChannelOn(), smsRecipient("+5511999990001"), "SMS"));

        List<Instant> attempts = awaitStatus(id, "SENT").get("attempts").valueStream()
                .map(attempt -> Instant.parse(attempt.get("at").asString()))
                .toList();

        assertThat(Duration.between(attempts.get(0), attempts.get(1))).isGreaterThanOrEqualTo(FIRST_RETRY_INTERVAL);
        assertThat(Duration.between(attempts.get(1), attempts.get(2))).isGreaterThanOrEqualTo(SECOND_RETRY_INTERVAL);
    }

    @Test
    void anSmsThatAlwaysFailsGivesUpAfterFourAttemptsAndGoesToTheParkingLot() {
        String id = sendOne(generic(newUserWithEveryChannelOn(), smsRecipient("+5511999990000"), "SMS"));

        JsonNode failed = awaitStatus(id, "FAILED");

        assertThat(successes(failed)).containsExactly(false, false, false, false);
        assertThat(failed.get("sentAt").isNull()).isTrue();
        Message parked = awaitInParkingLot(id);
        assertThat(parked.getMessageProperties().<Integer>getHeader(OutboxRelay.ATTEMPT_HEADER)).isEqualTo(4);
    }

    @Test
    void aPushToAFlakyDeviceIsSentOnTheThirdAttempt() {
        String id = sendOne(generic(newUser(), pushRecipient("flaky-device"), "PUSH"));

        assertThat(successes(awaitStatus(id, "SENT"))).containsExactly(false, false, true);
    }

    @Test
    void aPushToADeviceThatAlwaysFailsGivesUp() {
        String id = sendOne(generic(newUser(), pushRecipient("fail-device"), "PUSH"));

        assertThat(successes(awaitStatus(id, "FAILED"))).hasSize(4).containsOnly(false);
    }

    @Test
    void anEmailToTheFailingDomainGivesUpWithoutReachingTheInbox() {
        String email = "ana-" + System.nanoTime() + "@falha.exemplo";

        String id = sendOne(generic(newUser(), emailRecipient(email), "EMAIL"));

        assertThat(successes(awaitStatus(id, "FAILED"))).hasSize(4).containsOnly(false);
        assertThat(mailbox.messagesTo(email)).isEmpty();
    }

    @Test
    void aMessageTheConsumerCannotReadIsDeadLetteredToTheParkingLotInsteadOfLooping() {
        String unreadable = "not-an-id-" + UUID.randomUUID();

        rabbit.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.SMS_QUEUE, unreadable, message -> {
            message.getMessageProperties().setHeader(OutboxRelay.ATTEMPT_HEADER, 1);
            return message;
        });

        assertThat(awaitInParkingLot(unreadable)).isNotNull();
    }

    /** Other tests park messages too, so this takes them out until it finds the one it wants. */
    private Message awaitInParkingLot(String body) {
        Message[] found = new Message[1];
        await().atMost(PATIENCE).until(() -> {
            Message message = rabbit.receive(RabbitTopology.PARKING_LOT, 500);
            if (message != null && body.equals(new String(message.getBody(), StandardCharsets.UTF_8))) {
                found[0] = message;
            }
            return found[0] != null;
        });
        return found[0];
    }
}
