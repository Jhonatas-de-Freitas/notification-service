package io.github.jhonatasdefreitas.notifications.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitOperations.OperationsCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jhonatasdefreitas.notifications.persistence.OutboxMessage;
import io.github.jhonatasdefreitas.notifications.persistence.OutboxRepository;

class OutboxRelayTest {

    private static final UUID ID = UUID.randomUUID();

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final RabbitOperations channel = mock(RabbitOperations.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final OutboxRelay relay = new OutboxRelay(outbox, rabbit, new TransactionTemplate(transactionManager));

    private final List<OutboxMessage> batch = List.of(new OutboxMessage(ID, "notifications.sms.wait.5000", 2, Instant.now()));

    @BeforeEach
    void runTheCallbackOnTheChannel() {
        when(rabbit.invoke(any())).thenAnswer(call -> call.<OperationsCallback<?>>getArgument(0).doInRabbit(channel));
    }

    @Test
    void publishesTheRowToItsQueueWithTheAttemptNumber() {
        when(outbox.lockNextBatch(100)).thenReturn(batch);

        relay.publishPending();

        ArgumentCaptor<Message> published = ArgumentCaptor.forClass(Message.class);
        verify(channel).send(eq("notifications"), eq("notifications.sms.wait.5000"), published.capture());
        assertThat(new String(published.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo(ID.toString());
        assertThat(published.getValue().getMessageProperties().<Integer>getHeader("x-attempt")).isEqualTo(2);
    }

    @Test
    void deletesTheRowOnlyAfterTheBrokerConfirms() {
        when(outbox.lockNextBatch(100)).thenReturn(batch);

        relay.publishPending();

        InOrder order = inOrder(channel, outbox, transactionManager);
        order.verify(channel).send(any(), any(), any(Message.class));
        order.verify(channel).waitForConfirmsOrDie(anyLong());
        order.verify(outbox).deleteAllInBatch(batch);
        order.verify(transactionManager).commit(any());
    }

    @Test
    void keepsTheRowWhenTheBrokerDoesNotConfirm() {
        when(outbox.lockNextBatch(100)).thenReturn(batch);
        doThrow(new AmqpTimeoutException("no confirm")).when(channel).waitForConfirmsOrDie(anyLong());

        relay.publishPending();

        verify(outbox, never()).deleteAllInBatch(any());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void leavesTheBrokerAloneWhenThereIsNothingToPublish() {
        when(outbox.lockNextBatch(100)).thenReturn(List.of());

        relay.publishPending();

        verifyNoInteractions(rabbit);
    }
}
