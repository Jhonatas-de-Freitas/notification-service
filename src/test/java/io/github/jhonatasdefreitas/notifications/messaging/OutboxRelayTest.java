package io.github.jhonatasdefreitas.notifications.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jhonatasdefreitas.notifications.messaging.QueuePublisher.Outgoing;
import io.github.jhonatasdefreitas.notifications.persistence.OutboxMessage;
import io.github.jhonatasdefreitas.notifications.persistence.OutboxRepository;

class OutboxRelayTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final QueuePublisher publisher = mock(QueuePublisher.class);
    private final AmqpAdmin admin = mock(AmqpAdmin.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final OutboxRelay relay = new OutboxRelay(outbox, publisher, admin,
            new TransactionTemplate(transactionManager));

    private final OutboxMessage retry = new OutboxMessage(ID, "notifications.sms.wait.5000", 2, Instant.now());
    private final OutboxMessage first = new OutboxMessage(OTHER_ID, "notifications.email", 1, Instant.now());

    @Test
    void publishesEachRowToItsQueueWithTheAttemptNumber() {
        when(outbox.lockNextBatch(100)).thenReturn(List.of(retry, first));

        relay.publishPending();

        verify(publisher).publish(List.of(
                new Outgoing("notifications.sms.wait.5000", ID, 2, 0),
                new Outgoing("notifications.email", OTHER_ID, 1, 0)));
    }

    @Test
    void deletesTheRowsOnlyAfterTheBrokerHasTheMessages() {
        when(outbox.lockNextBatch(100)).thenReturn(List.of(retry, first));

        relay.publishPending();

        InOrder order = inOrder(publisher, outbox, transactionManager);
        order.verify(publisher).publish(any());
        order.verify(outbox).deleteAllInBatch(List.of(retry, first));
        order.verify(transactionManager).commit(any());
        verifyNoInteractions(admin);
    }

    @Test
    void keepsTheRowsWhenTheBrokerDoesNotConfirm() {
        when(outbox.lockNextBatch(100)).thenReturn(List.of(retry, first));
        when(publisher.publish(any())).thenThrow(new AmqpTimeoutException("no confirm"));

        relay.publishPending();

        verify(outbox, never()).deleteAllInBatch(any());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void keepsTheRowOfAMessageThatNoQueueTookAndDeclaresTheQueuesAgain() {
        when(outbox.lockNextBatch(100)).thenReturn(List.of(retry, first));
        when(publisher.publish(any())).thenReturn(Set.of(0));

        relay.publishPending();

        verify(outbox).deleteAllInBatch(List.of(first));
        verify(transactionManager).commit(any());
        verify(admin).initialize();
    }

    @Test
    void leavesTheBrokerAloneWhenThereIsNothingToPublish() {
        when(outbox.lockNextBatch(100)).thenReturn(List.of());

        relay.publishPending();

        verifyNoInteractions(publisher);
    }
}
