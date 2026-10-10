package io.github.jhonatasdefreitas.notifications.provider;

import static io.github.jhonatasdefreitas.notifications.Fixtures.pending;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.domain.Recipient;

class SimulatedSendersTest {

    private final SimulatedSmsSender sms = new SimulatedSmsSender();
    private final SimulatedPushSender push = new SimulatedPushSender();

    @Test
    void smsToAnOrdinaryNumberWorksOnTheFirstAttempt() {
        assertThatCode(() -> sms.send(smsTo("+5511999990002"), 1)).doesNotThrowAnyException();
    }

    @Test
    void smsToANumberEnding0000NeverWorks() {
        Notification notification = smsTo("+5511999990000");

        for (int attempt = 1; attempt <= 4; attempt++) {
            int current = attempt;
            assertThatThrownBy(() -> sms.send(notification, current)).isInstanceOf(DeliveryException.class);
        }
    }

    @Test
    void smsToANumberEnding0001WorksOnTheThirdAttempt() {
        Notification notification = smsTo("+5511999990001");

        assertThatThrownBy(() -> sms.send(notification, 1)).isInstanceOf(DeliveryException.class);
        assertThatThrownBy(() -> sms.send(notification, 2)).isInstanceOf(DeliveryException.class);
        assertThatCode(() -> sms.send(notification, 3)).doesNotThrowAnyException();
    }

    @Test
    void pushToAnOrdinaryDeviceWorksOnTheFirstAttempt() {
        assertThatCode(() -> push.send(pushTo("device-abc"), 1)).doesNotThrowAnyException();
    }

    @Test
    void pushToAFailDeviceNeverWorks() {
        Notification notification = pushTo("fail-abc");

        for (int attempt = 1; attempt <= 4; attempt++) {
            int current = attempt;
            assertThatThrownBy(() -> push.send(notification, current)).isInstanceOf(DeliveryException.class);
        }
    }

    @Test
    void pushToAFlakyDeviceWorksOnTheThirdAttempt() {
        Notification notification = pushTo("flaky-abc");

        assertThatThrownBy(() -> push.send(notification, 1)).isInstanceOf(DeliveryException.class);
        assertThatThrownBy(() -> push.send(notification, 2)).isInstanceOf(DeliveryException.class);
        assertThatCode(() -> push.send(notification, 3)).doesNotThrowAnyException();
    }

    private static Notification smsTo(String phone) {
        return pending(Channel.SMS, new Recipient("Ana Souza", null, phone, null));
    }

    private static Notification pushTo(String deviceToken) {
        return pending(Channel.PUSH, new Recipient("Ana Souza", null, null, deviceToken));
    }
}
