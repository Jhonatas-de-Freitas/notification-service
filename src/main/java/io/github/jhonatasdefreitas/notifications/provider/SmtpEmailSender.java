package io.github.jhonatasdefreitas.notifications.provider;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.domain.Recipient;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

@Component
class SmtpEmailSender implements ChannelSender {

    /** Addresses in this domain always fail, to show the retries without taking the SMTP server down. */
    private static final String FAILING_DOMAIN = "@falha.exemplo";

    private final JavaMailSender mailSender;
    private final String from;

    SmtpEmailSender(JavaMailSender mailSender, @Value("${notifications.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public void send(Notification notification, int attempt) {
        Recipient recipient = notification.getRecipient();
        if (recipient.email().toLowerCase(Locale.ROOT).endsWith(FAILING_DOMAIN)) {
            throw new DeliveryException("Mail server refused the recipient (simulated failure)");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(new InternetAddress(recipient.email(), recipient.name(), StandardCharsets.UTF_8.name()));
            helper.setSubject(notification.getSubject());
            helper.setText(notification.getBody(), true);
            message.setHeader("X-Notification-Id", notification.getId().toString());
            mailSender.send(message);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            throw new DeliveryException("SMTP: " + e.getMessage(), e);
        }
    }
}
