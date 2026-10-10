package io.github.jhonatasdefreitas.notifications.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a template has to say, before it takes the shape of a channel. E-mail uses everything;
 * SMS and push only have room for the summary.
 */
public record Content(String subject, Tone tone, List<String> paragraphs, List<Item> items, BigDecimal total,
        String summary) {

    public enum Tone {
        NEUTRAL,
        GOOD_NEWS,
        BAD_NEWS
    }

    public record Item(String name, int quantity, BigDecimal unitPrice) {
    }

    public Message forChannel(Channel channel, String recipientName) {
        return switch (channel) {
            case EMAIL -> new Message(subject, EmailLayout.render(this, recipientName));
            case SMS -> new Message(subject, MessageTemplates.STORE + ": " + summary);
            case PUSH -> new Message(subject, summary);
        };
    }
}
