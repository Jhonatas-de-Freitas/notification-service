package io.github.jhonatasdefreitas.notifications.provider;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.Notification;

public interface ChannelSender {

    Channel channel();

    /**
     * Hands the notification to the provider. Returning means it was accepted; any exception
     * counts as a failed attempt.
     */
    void send(Notification notification, int attempt);
}
