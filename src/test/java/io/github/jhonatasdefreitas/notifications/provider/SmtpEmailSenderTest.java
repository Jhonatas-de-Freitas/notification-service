package io.github.jhonatasdefreitas.notifications.provider;

import static io.github.jhonatasdefreitas.notifications.Fixtures.ANA;
import static io.github.jhonatasdefreitas.notifications.Fixtures.pending;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.domain.Recipient;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

class SmtpEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SmtpEmailSender sender = new SmtpEmailSender(mailSender, "Loja Exemplo <loja@exemplo.com>");

    @Test
    void sendsAnHtmlMessageToTheRecipient() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        Notification notification = pending(Channel.EMAIL, ANA);
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(notification, "id", id);

        sender.send(notification, 1);

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        MimeMessage message = sent.getValue();
        assertThat(message.getSubject()).isEqualTo("Olá");
        assertThat(message.getFrom()[0]).hasToString("Loja Exemplo <loja@exemplo.com>");
        assertThat(message.getRecipients(RecipientType.TO)[0]).hasToString("Ana Souza <ana@exemplo.com>");
        assertThat(message.getDataHandler().getContentType()).startsWith("text/html");
        assertThat(message.getHeader("X-Notification-Id")).containsExactly(id.toString());
    }

    @Test
    void anAddressInTheFailingDomainFailsBeforeReachingTheServer() {
        Notification notification = pending(Channel.EMAIL, new Recipient("Ana Souza", "ana@FALHA.exemplo", null, null));

        assertThatThrownBy(() -> sender.send(notification, 1)).isInstanceOf(DeliveryException.class);
        verifyNoInteractions(mailSender);
    }

    @Test
    void anSmtpErrorBecomesAFailedDelivery() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("Connection refused")).when(mailSender).send(any(MimeMessage.class));
        Notification notification = pending(Channel.EMAIL, ANA);
        ReflectionTestUtils.setField(notification, "id", UUID.randomUUID());

        assertThatThrownBy(() -> sender.send(notification, 1))
                .isInstanceOf(DeliveryException.class)
                .hasMessage("SMTP: Connection refused");
    }
}
