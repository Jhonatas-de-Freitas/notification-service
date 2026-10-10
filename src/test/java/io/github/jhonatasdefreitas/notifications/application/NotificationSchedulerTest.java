package io.github.jhonatasdefreitas.notifications.application;

import static io.github.jhonatasdefreitas.notifications.Fixtures.ANA;
import static io.github.jhonatasdefreitas.notifications.Fixtures.NOW;
import static io.github.jhonatasdefreitas.notifications.Fixtures.scheduled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.domain.NotificationStatus;
import io.github.jhonatasdefreitas.notifications.messaging.DeliveryQueue;
import io.github.jhonatasdefreitas.notifications.persistence.NotificationRepository;

class NotificationSchedulerTest {

    private static final Instant LATER = NOW.plusSeconds(60);

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final DeliveryQueue deliveryQueue = mock(DeliveryQueue.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final NotificationScheduler scheduler = new NotificationScheduler(notifications, deliveryQueue,
            new TransactionTemplate(transactionManager), Clock.fixed(LATER, ZoneOffset.UTC));

    @Test
    void aDueNotificationBecomesPendingAndIsQueuedInTheSameTransaction() {
        Notification due = scheduled(Channel.EMAIL, ANA, NOW.plusSeconds(10));
        when(notifications.lockDue(LATER, 100)).thenReturn(List.of(due));

        scheduler.releaseDue();

        assertThat(due.getStatus()).isEqualTo(NotificationStatus.PENDING);
        InOrder order = inOrder(notifications, deliveryQueue, transactionManager);
        order.verify(notifications).lockDue(LATER, 100);
        order.verify(deliveryQueue).enqueue(due);
        order.verify(transactionManager).commit(any());
    }

    @Test
    void queuesNothingWhenNothingIsDue() {
        when(notifications.lockDue(LATER, 100)).thenReturn(List.of());

        scheduler.releaseDue();

        verify(deliveryQueue, never()).enqueue(any());
    }

    @Test
    void aFullBatchIsFollowedByAnotherUntilNothingIsLeft() {
        List<Notification> fullBatch = Stream.generate(() -> scheduled(Channel.EMAIL, ANA, NOW.plusSeconds(10)))
                .limit(100)
                .toList();
        Notification last = scheduled(Channel.PUSH, ANA, NOW.plusSeconds(10));
        when(notifications.lockDue(LATER, 100)).thenReturn(fullBatch, List.of(last));

        scheduler.releaseDue();

        verify(notifications, times(2)).lockDue(LATER, 100);
        verify(deliveryQueue, times(101)).enqueue(any());
        verify(transactionManager, times(2)).commit(any());
    }
}
