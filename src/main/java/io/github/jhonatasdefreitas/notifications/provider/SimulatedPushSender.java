package io.github.jhonatasdefreitas.notifications.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.Notification;

/**
 * Stands in for a push gateway. The start of the device token chooses the behaviour:
 * {@code fail-} never works and {@code flaky-} works on the third attempt.
 */
@Component
class SimulatedPushSender implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPushSender.class);

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    public void send(Notification notification, int attempt) {
        String deviceToken = notification.getRecipient().deviceToken();
        if (deviceToken.startsWith("fail-")) {
            throw new DeliveryException("Device is no longer registered (simulated failure)");
        }
        if (deviceToken.startsWith("flaky-") && attempt <= 2) {
            throw new DeliveryException("Device did not respond (simulated failure)");
        }
        log.info("Push for notification {} to {}: {} - {}", notification.getId(), deviceToken,
                notification.getSubject(), notification.getBody());
    }
}
