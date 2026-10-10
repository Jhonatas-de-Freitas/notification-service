package io.github.jhonatasdefreitas.notifications.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Return;
import com.rabbitmq.client.ReturnCallback;
import com.rabbitmq.client.ReturnListener;

import io.github.jhonatasdefreitas.notifications.messaging.QueuePublisher.Outgoing;

class QueuePublisherTest {

    private static final UUID ID = UUID.randomUUID();
    private static final String DELETED_QUEUE = "notifications.sms.wait.5000";

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final Channel channel = mock(Channel.class);
    private final ReturnListener registered = mock(ReturnListener.class);
    private final AtomicReference<ReturnCallback> returns = new AtomicReference<>();
    private final QueuePublisher publisher = new QueuePublisher(rabbit);

    @BeforeEach
    void runTheCallbackOnTheChannel() throws Exception {
        when(rabbit.execute(any())).thenAnswer(call -> call.<ChannelCallback<?>>getArgument(0).doInRabbit(channel));
        when(channel.addReturnListener(any(ReturnCallback.class))).thenAnswer(call -> {
            returns.set(call.getArgument(0));
            return registered;
        });
        // What the broker does with a mandatory message for a queue that is not there.
        doAnswer(call -> {
            returns.get().handle(new Return(312, "NO_ROUTE", call.getArgument(0), call.getArgument(1),
                    call.getArgument(3), call.getArgument(4)));
            return null;
        }).when(channel).basicPublish(any(), eq(DELETED_QUEUE), anyBoolean(), any(), any());
    }

    @Test
    void publishesTheNotificationIdAsAPersistentMandatoryMessageWithItsHeaders() throws Exception {
        publisher.publish(List.of(new Outgoing("notifications.email", ID, 3, 1)));

        ArgumentCaptor<AMQP.BasicProperties> properties = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(channel).basicPublish(eq("notifications"), eq("notifications.email"), eq(true), properties.capture(),
                body.capture());
        assertThat(new String(body.getValue(), StandardCharsets.UTF_8)).isEqualTo(ID.toString());
        assertThat(properties.getValue().getDeliveryMode()).isEqualTo(2);
        assertThat(properties.getValue().getHeaders()).containsEntry("x-attempt", 3).containsEntry("x-redeliveries", 1);
    }

    @Test
    void returnsNothingWhenEveryMessageFoundAQueue() {
        assertThat(publisher.publish(List.of(
                new Outgoing("notifications.email", ID, 1, 0),
                new Outgoing("notifications.push", ID, 1, 0)))).isEmpty();
    }

    @Test
    void pointsAtTheMessagesThatNoQueueTook() {
        assertThat(publisher.publish(List.of(
                new Outgoing("notifications.email", ID, 1, 0),
                new Outgoing(DELETED_QUEUE, ID, 2, 0),
                new Outgoing("notifications.push", ID, 1, 0),
                new Outgoing(DELETED_QUEUE, UUID.randomUUID(), 2, 0)))).containsExactly(1, 3);
    }

    @Test
    void waitsForTheConfirmsBeforeAnsweringAndLeavesTheChannelClean() throws Exception {
        publisher.publish(List.of(new Outgoing("notifications.email", ID, 1, 0)));

        InOrder order = inOrder(channel);
        order.verify(channel).basicPublish(any(), any(), anyBoolean(), any(), any());
        order.verify(channel).waitForConfirmsOrDie(anyLong());
        order.verify(channel).removeReturnListener(registered);
    }

    @Test
    void failsWhenTheBrokerDoesNotConfirmAndStillLeavesTheChannelClean() throws Exception {
        TimeoutException noConfirm = new TimeoutException();
        doThrow(noConfirm).when(channel).waitForConfirmsOrDie(anyLong());

        assertThatThrownBy(() -> publisher.publish(List.of(new Outgoing("notifications.email", ID, 1, 0))))
                .isSameAs(noConfirm);
        verify(channel).removeReturnListener(registered);
    }
}
