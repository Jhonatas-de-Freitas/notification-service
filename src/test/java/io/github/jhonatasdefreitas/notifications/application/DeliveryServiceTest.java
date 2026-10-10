package io.github.jhonatasdefreitas.notifications.application;

import static io.github.jhonatasdefreitas.notifications.Fixtures.ANA;
import static io.github.jhonatasdefreitas.notifications.Fixtures.NOW;
import static io.github.jhonatasdefreitas.notifications.Fixtures.pending;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jhonatasdefreitas.notifications.domain.Attempt;
import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.domain.NotificationStatus;
import io.github.jhonatasdefreitas.notifications.domain.RetryPolicy;
import io.github.jhonatasdefreitas.notifications.messaging.DeliveryQueue;
import io.github.jhonatasdefreitas.notifications.persistence.NotificationRepository;
import io.github.jhonatasdefreitas.notifications.provider.ChannelSender;
import io.github.jhonatasdefreitas.notifications.provider.DeliveryException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DeliveryServiceTest {

    private static final UUID ID = UUID.randomUUID();
    private static final RetryPolicy POLICY = new RetryPolicy(
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45)));

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final ChannelSender sender = mock(ChannelSender.class);
    private final DeliveryQueue deliveryQueue = mock(DeliveryQueue.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final Notification notification = pending(Channel.SMS, ANA);

    private DeliveryService service;

    @BeforeEach
    void setUp() {
        when(sender.channel()).thenReturn(Channel.SMS);
        when(notifications.findByIdForUpdate(ID)).thenReturn(Optional.of(notification));
        service = new DeliveryService(notifications, List.of(sender), POLICY, deliveryQueue, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void marksTheNotificationSentWhenTheProviderAccepts() {
        service.deliver(ID, 1);

        verify(sender).send(notification, 1);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isEqualTo(NOW);
        verifyNoInteractions(deliveryQueue);
        assertThat(deliveries("sent")).isEqualTo(1);
    }

    @Test
    void aFailureSchedulesTheNextAttemptAfterTheFirstInterval() {
        doThrow(new DeliveryException("gateway down")).when(sender).send(notification, 1);

        service.deliver(ID, 1);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(notification.getAttempts()).extracting(Attempt::getError).containsExactly("gateway down");
        verify(deliveryQueue).retryAfter(notification, 2, Duration.ofSeconds(5));
        assertThat(deliveries("retry")).isEqualTo(1);
    }

    @Test
    void eachFailureWaitsLongerThanTheOneBefore() {
        doThrow(new DeliveryException("gateway down")).when(sender).send(any(), anyInt());

        service.deliver(ID, 1);
        service.deliver(ID, 2);
        service.deliver(ID, 3);

        verify(deliveryQueue).retryAfter(notification, 2, Duration.ofSeconds(5));
        verify(deliveryQueue).retryAfter(notification, 3, Duration.ofSeconds(15));
        verify(deliveryQueue).retryAfter(notification, 4, Duration.ofSeconds(45));
        verify(deliveryQueue, never()).park(any());
    }

    @Test
    void theFourthFailureGivesUpAndParksTheMessage() {
        doThrow(new DeliveryException("gateway down")).when(sender).send(any(), anyInt());

        for (int attempt = 1; attempt <= 4; attempt++) {
            service.deliver(ID, attempt);
        }

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttempts()).hasSize(4).noneMatch(Attempt::isSuccess);
        verify(deliveryQueue).park(notification);
        assertThat(deliveries("failed")).isEqualTo(1);
    }

    @Test
    void succeedsOnTheThirdAttemptAfterTwoFailures() {
        doThrow(new DeliveryException("gateway down")).when(sender).send(notification, 1);
        doThrow(new DeliveryException("gateway down")).when(sender).send(notification, 2);

        service.deliver(ID, 1);
        service.deliver(ID, 2);
        service.deliver(ID, 3);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getAttempts()).extracting(Attempt::isSuccess).containsExactly(false, false, true);
    }

    @Test
    void aMessageDeliveredTwiceDoesNotReachTheProviderAgain() {
        service.deliver(ID, 1);
        service.deliver(ID, 1);

        verify(sender).send(notification, 1);
        assertThat(notification.getAttempts()).hasSize(1);
        assertThat(deliveries("duplicate")).isEqualTo(1);
    }

    @Test
    void aRepeatedMessageForAnAttemptAlreadyRecordedIsIgnored() {
        doThrow(new DeliveryException("gateway down")).when(sender).send(notification, 1);

        service.deliver(ID, 1);
        service.deliver(ID, 1);

        verify(sender).send(notification, 1);
        assertThat(notification.getAttempts()).hasSize(1);
        verify(deliveryQueue).retryAfter(notification, 2, Duration.ofSeconds(5));
    }

    @Test
    void aMessageForAnUnknownNotificationIsDropped() {
        service.deliver(UUID.randomUUID(), 1);

        verifyNoInteractions(deliveryQueue);
        verify(sender, never()).send(any(), anyInt());
    }

    private double deliveries(String result) {
        return metrics.counter("notifications.deliveries", "channel", "SMS", "result", result).count();
    }
}
