package io.github.jhonatasdefreitas.notifications.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_preferences")
public class Preferences {

    @Id
    private String userId;
    private boolean email;
    private boolean sms;
    private boolean push;

    protected Preferences() {
    }

    public Preferences(String userId, boolean email, boolean sms, boolean push) {
        this.userId = userId;
        this.email = email;
        this.sms = sms;
        this.push = push;
    }

    public static Preferences defaultsFor(String userId) {
        return new Preferences(userId, true, false, true);
    }

    public boolean allows(Channel channel) {
        return switch (channel) {
            case EMAIL -> email;
            case SMS -> sms;
            case PUSH -> push;
        };
    }

    public String getUserId() {
        return userId;
    }

    public boolean isEmail() {
        return email;
    }

    public boolean isSms() {
        return sms;
    }

    public boolean isPush() {
        return push;
    }
}
