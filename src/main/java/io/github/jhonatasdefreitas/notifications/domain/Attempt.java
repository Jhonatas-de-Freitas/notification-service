package io.github.jhonatasdefreitas.notifications.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_attempts")
public class Attempt {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id")
    private Notification notification;

    @Column(name = "attempt_number")
    private int number;

    @Column(name = "attempted_at")
    private Instant at;

    private boolean success;
    private String error;

    protected Attempt() {
    }

    Attempt(Notification notification, int number, Instant at, String error) {
        this.notification = notification;
        this.number = number;
        this.at = at;
        this.success = error == null;
        this.error = error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    public int getNumber() {
        return number;
    }

    public Instant getAt() {
        return at;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getError() {
        return error;
    }
}
