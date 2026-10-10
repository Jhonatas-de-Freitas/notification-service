package io.github.jhonatasdefreitas.notifications.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.Notification;

/**
 * Stands in for an SMS gateway. The last digits of the phone choose the behaviour, so a failure
 * can be shown on demand: {@code 0000} never works and {@code 0001} works on the third attempt.
 */
@Component
class SimulatedSmsSender implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedSmsSender.class);

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public void send(Notification notification, int attempt) {
        String phone = notification.getRecipient().phone();
        if (phone.endsWith("0000")) {
            throw new DeliveryException("Operadora recusou o número " + phone + " (falha simulada)");
        }
        if (phone.endsWith("0001") && attempt <= 2) {
            throw new DeliveryException("Operadora indisponível no momento (falha simulada)");
        }
        log.info("SMS for notification {} to {}: {}", notification.getId(), phone, notification.getBody());
    }
}
