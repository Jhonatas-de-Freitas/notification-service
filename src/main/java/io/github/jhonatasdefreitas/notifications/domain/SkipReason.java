package io.github.jhonatasdefreitas.notifications.domain;

public enum SkipReason {
    /** The user turned the channel off in the preferences. */
    CHANNEL_DISABLED,
    /** The request has no address for the channel, such as an SMS without a phone. */
    MISSING_DESTINATION
}
