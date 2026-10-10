package io.github.jhonatasdefreitas.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record Recipient(
        @Column(name = "recipient_name") String name,
        @Column(name = "recipient_email") String email,
        @Column(name = "recipient_phone") String phone,
        @Column(name = "recipient_device_token") String deviceToken) {

    public Recipient {
        email = blankToNull(email);
        phone = blankToNull(phone);
        deviceToken = blankToNull(deviceToken);
    }

    /** Null when the recipient cannot be reached through the channel. */
    public String addressFor(Channel channel) {
        return switch (channel) {
            case EMAIL -> email;
            case SMS -> phone;
            case PUSH -> deviceToken;
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
