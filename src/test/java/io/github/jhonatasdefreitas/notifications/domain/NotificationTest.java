package io.github.jhonatasdefreitas.notifications.domain;

import static io.github.jhonatasdefreitas.notifications.Fixtures.ANA;
import static io.github.jhonatasdefreitas.notifications.Fixtures.EVERYTHING_ON;
import static io.github.jhonatasdefreitas.notifications.Fixtures.NOW;
import static io.github.jhonatasdefreitas.notifications.Fixtures.pending;
import static io.github.jhonatasdefreitas.notifications.Fixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class NotificationTest {

    private static final Message MESSAGE = new Message("Olá", "Texto livre");

    @Test
    void startsPendingWhenTheChannelIsOnAndHasAnAddress() {
        Notification notification = create(Channel.EMAIL, ANA, EVERYTHING_ON, null);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getSkipReason()).isNull();
    }

    @Test
    void isSkippedWhenTheUserTurnedTheChannelOff() {
        Preferences smsOff = Preferences.defaultsFor("c-1001");

        Notification notification = create(Channel.SMS, ANA, smsOff, null);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(notification.getSkipReason()).isEqualTo(SkipReason.CHANNEL_DISABLED);
    }

    @Test
    void isSkippedWhenTheRecipientHasNoAddressForTheChannel() {
        Recipient withoutPhone = new Recipient("Ana Souza", "ana@exemplo.com", " ", null);

        Notification notification = create(Channel.SMS, withoutPhone, EVERYTHING_ON, null);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(notification.getSkipReason()).isEqualTo(SkipReason.MISSING_DESTINATION);
    }

    @Test
    void isScheduledOnlyWhenTheTimeIsStillAhead() {
        assertThat(create(Channel.EMAIL, ANA, EVERYTHING_ON, NOW.plusSeconds(10)).getStatus())
                .isEqualTo(NotificationStatus.SCHEDULED);
        assertThat(create(Channel.EMAIL, ANA, EVERYTHING_ON, NOW.minusSeconds(10)).getStatus())
                .isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void aDisabledChannelIsSkippedEvenWhenScheduled() {
        Notification notification = create(Channel.SMS, ANA, Preferences.defaultsFor("c-1001"), NOW.plusSeconds(10));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
    }

    @Test
    void aScheduledNotificationAwaitsItsFirstAttemptOnlyAfterItIsReleased() {
        Notification notification = create(Channel.EMAIL, ANA, EVERYTHING_ON, NOW.plusSeconds(10));
        assertThat(notification.awaitsAttempt(1)).isFalse();

        notification.release();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.awaitsAttempt(1)).isTrue();
    }

    @Test
    void onlyAScheduledNotificationCanBeReleased() {
        Notification sent = pending(Channel.EMAIL, ANA);
        sent.recordSuccess(NOW);

        assertThatThrownBy(sent::release).isInstanceOf(IllegalStateException.class);
        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void isDueAtTheScheduledTimeOrAtCreationWhenThereIsNoneAhead() {
        assertThat(create(Channel.EMAIL, ANA, EVERYTHING_ON, NOW.plusSeconds(10)).dueAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(create(Channel.EMAIL, ANA, EVERYTHING_ON, NOW.minusSeconds(10)).dueAt()).isEqualTo(NOW);
        assertThat(create(Channel.EMAIL, ANA, EVERYTHING_ON, null).dueAt()).isEqualTo(NOW);
    }

    @Test
    void successOnTheFirstAttemptMarksItSent() {
        Notification notification = pending(Channel.EMAIL, ANA);

        notification.recordSuccess(NOW.plusSeconds(1));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(notification.getAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getNumber()).isEqualTo(1);
            assertThat(attempt.isSuccess()).isTrue();
            assertThat(attempt.getError()).isNull();
        });
    }

    @Test
    void keepsEveryAttemptInOrder() {
        Notification notification = pending(Channel.SMS, ANA);

        notification.recordFailure(NOW.plusSeconds(1), "first", true);
        notification.recordFailure(NOW.plusSeconds(2), "second", true);
        notification.recordSuccess(NOW.plusSeconds(3));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getAttempts()).extracting(Attempt::getNumber).containsExactly(1, 2, 3);
        assertThat(notification.getAttempts()).extracting(Attempt::isSuccess).containsExactly(false, false, true);
        assertThat(notification.getAttempts()).extracting(Attempt::getError).containsExactly("first", "second", null);
    }

    @Test
    void failureWithRetryLeftKeepsItRetrying() {
        Notification notification = pending(Channel.SMS, ANA);

        notification.recordFailure(NOW, "gateway down", true);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(notification.getSentAt()).isNull();
    }

    @Test
    void failureWithoutRetryLeftMarksItFailed() {
        Notification notification = pending(Channel.SMS, ANA);

        notification.recordFailure(NOW, "gateway down", false);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void awaitsOnlyTheNextAttempt() {
        Notification notification = pending(Channel.SMS, ANA);
        assertThat(notification.awaitsAttempt(1)).isTrue();
        assertThat(notification.awaitsAttempt(2)).isFalse();

        notification.recordFailure(NOW, "gateway down", true);

        assertThat(notification.awaitsAttempt(1)).isFalse();
        assertThat(notification.awaitsAttempt(2)).isTrue();
    }

    @Test
    void awaitsNothingOnceSettled() {
        Notification sent = pending(Channel.EMAIL, ANA);
        sent.recordSuccess(NOW);
        Notification failed = pending(Channel.EMAIL, ANA);
        failed.recordFailure(NOW, "rejected", false);
        Notification scheduled = create(Channel.EMAIL, ANA, EVERYTHING_ON, NOW.plusSeconds(60));

        assertThat(sent.awaitsAttempt(2)).isFalse();
        assertThat(failed.awaitsAttempt(2)).isFalse();
        assertThat(scheduled.awaitsAttempt(1)).isFalse();
    }

    @Test
    void cutsAnErrorTooLongForTheColumn() {
        Notification notification = pending(Channel.EMAIL, ANA);

        notification.recordFailure(NOW, "x".repeat(2_000), true);

        assertThat(notification.getAttempts().getFirst().getError()).hasSize(500);
    }

    private static Notification create(Channel channel, Recipient recipient, Preferences preferences,
            Instant scheduledAt) {
        return new Notification(UUID.randomUUID(), request(recipient, scheduledAt), channel, MESSAGE, preferences, NOW);
    }
}
