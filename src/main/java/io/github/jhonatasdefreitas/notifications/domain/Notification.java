package io.github.jhonatasdefreitas.notifications.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID requestId;
    private String userId;
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    private Template template;

    @Embedded
    private Recipient recipient;

    private String subject;
    private String body;

    @Enumerated(EnumType.STRING)
    private NotificationStatus status;

    @Enumerated(EnumType.STRING)
    private SkipReason skipReason;

    private Instant scheduledAt;
    private Instant sentAt;
    private Instant createdAt;

    // Always read together with the notification. The batch size keeps a page of results at two queries.
    @OneToMany(mappedBy = "notification", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @OrderBy("number")
    @BatchSize(size = 100)
    private List<Attempt> attempts = new ArrayList<>();

    protected Notification() {
    }

    public Notification(UUID requestId, NewNotification request, Channel channel, Message message,
            Preferences preferences, Instant now) {
        this.requestId = requestId;
        this.userId = request.userId();
        this.orderId = request.orderId();
        this.channel = channel;
        this.template = request.template();
        this.recipient = request.recipient();
        this.subject = message.subject();
        this.body = message.body();
        this.scheduledAt = request.scheduledAt();
        this.createdAt = now;

        if (!preferences.allows(channel)) {
            this.status = NotificationStatus.SKIPPED;
            this.skipReason = SkipReason.CHANNEL_DISABLED;
        } else if (recipient.addressFor(channel) == null) {
            this.status = NotificationStatus.SKIPPED;
            this.skipReason = SkipReason.MISSING_DESTINATION;
        } else if (scheduledAt != null && scheduledAt.isAfter(now)) {
            this.status = NotificationStatus.SCHEDULED;
        } else {
            this.status = NotificationStatus.PENDING;
        }
    }

    /**
     * True only for the next attempt of a notification still in flight. A message that RabbitMQ
     * delivers twice, or that arrives after the notification is settled, fails this check.
     */
    public boolean awaitsAttempt(int number) {
        boolean inFlight = status == NotificationStatus.PENDING || status == NotificationStatus.RETRYING;
        return inFlight && attempts.size() == number - 1;
    }

    public void release() {
        if (status != NotificationStatus.SCHEDULED) {
            throw new IllegalStateException("Notification " + id + " is " + status + ", not SCHEDULED");
        }
        status = NotificationStatus.PENDING;
    }

    /** When the delivery was meant to start: the scheduled time, or the creation when there is none ahead. */
    public Instant dueAt() {
        return scheduledAt != null && scheduledAt.isAfter(createdAt) ? scheduledAt : createdAt;
    }

    public void recordSuccess(Instant at) {
        attempts.add(new Attempt(this, attempts.size() + 1, at, null));
        status = NotificationStatus.SENT;
        sentAt = at;
    }

    public void recordFailure(Instant at, String error, boolean willRetry) {
        attempts.add(new Attempt(this, attempts.size() + 1, at, error == null ? "Unknown error" : error));
        status = willRetry ? NotificationStatus.RETRYING : NotificationStatus.FAILED;
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Channel getChannel() {
        return channel;
    }

    public Template getTemplate() {
        return template;
    }

    public Recipient getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public SkipReason getSkipReason() {
        return skipReason;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Attempt> getAttempts() {
        return Collections.unmodifiableList(attempts);
    }
}
