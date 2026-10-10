package io.github.jhonatasdefreitas.notifications.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.dao.DataAccessResourceFailureException;

import io.github.jhonatasdefreitas.notifications.domain.RetryPolicy;
import io.github.jhonatasdefreitas.notifications.messaging.QueuePublisher;
import io.github.jhonatasdefreitas.notifications.messaging.QueuePublisher.Outgoing;

class DeliveryListenerTest {

    private static final UUID ID = UUID.randomUUID();
    private static final RetryPolicy POLICY = new RetryPolicy(
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45)));
    private static final RuntimeException DATABASE_DOWN = new DataAccessResourceFailureException("connection refused");

    private final DeliveryService deliveries = mock(DeliveryService.class);
    private final QueuePublisher publisher = mock(QueuePublisher.class);
    private final DeliveryListener listener = new DeliveryListener(deliveries, publisher, POLICY);

    @Test
    void handsTheNotificationAndTheAttemptToTheDelivery() {
        listener.onMessage(message(ID.toString(), 2, null));

        verify(deliveries).deliver(ID, 2);
        verifyNoInteractions(publisher);
    }

    @Test
    void aDeliveryThatTheServiceCouldNotProcessWaitsInTheBrokerAndKeepsItsAttemptNumber() {
        doThrow(DATABASE_DOWN).when(deliveries).deliver(ID, 2);
        when(publisher.publish(any())).thenReturn(Set.of());

        assertThatCode(() -> listener.onMessage(message(ID.toString(), 2, null))).doesNotThrowAnyException();

        verify(publisher).publish(List.of(new Outgoing("notifications.sms.wait.5000", ID, 2, 1)));
    }

    @Test
    void eachRedeliveryWaitsLongerThanTheOneBefore() {
        doThrow(DATABASE_DOWN).when(deliveries).deliver(ID, 1);
        when(publisher.publish(any())).thenReturn(Set.of());

        listener.onMessage(message(ID.toString(), 1, 1));
        listener.onMessage(message(ID.toString(), 1, 2));

        verify(publisher).publish(List.of(new Outgoing("notifications.sms.wait.15000", ID, 1, 2)));
        verify(publisher).publish(List.of(new Outgoing("notifications.sms.wait.45000", ID, 1, 3)));
    }

    @Test
    void outOfRedeliveriesTheMessageIsRejectedToTheParkingLot() {
        doThrow(DATABASE_DOWN).when(deliveries).deliver(ID, 1);

        assertThatThrownBy(() -> listener.onMessage(message(ID.toString(), 1, 3)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCause(DATABASE_DOWN);
        verifyNoInteractions(publisher);
    }

    @Test
    void aMessageThatCouldNotBeSentToWaitIsRejectedInsteadOfAcknowledged() {
        doThrow(DATABASE_DOWN).when(deliveries).deliver(ID, 1);
        when(publisher.publish(any())).thenReturn(Set.of(0));

        assertThatThrownBy(() -> listener.onMessage(message(ID.toString(), 1, null)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void aBodyThatIsNotAnIdIsRejectedWithoutBeingTried() {
        assertThatThrownBy(() -> listener.onMessage(message("not-an-id", 1, null)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(deliveries, publisher);
    }

    @Test
    void aMessageWithoutTheAttemptNumberIsRejectedWithoutBeingTried() {
        assertThatThrownBy(() -> listener.onMessage(message(ID.toString(), null, null)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(deliveries, publisher);
    }

    private static Message message(String body, Integer attempt, Integer redeliveries) {
        MessageBuilder message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8));
        if (attempt != null) {
            message.setHeader("x-attempt", attempt);
        }
        if (redeliveries != null) {
            message.setHeader("x-redeliveries", redeliveries);
        }
        Message built = message.build();
        built.getMessageProperties().setConsumerQueue("notifications.sms");
        return built;
    }
}
