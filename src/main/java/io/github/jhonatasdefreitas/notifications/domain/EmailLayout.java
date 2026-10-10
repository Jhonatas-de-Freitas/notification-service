package io.github.jhonatasdefreitas.notifications.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.util.HtmlUtils;

import io.github.jhonatasdefreitas.notifications.domain.Content.Item;

/** Turns a {@link Content} into the HTML of the e-mail. Styles are inline because mail clients drop the rest. */
final class EmailLayout {

    private static final String LAYOUT = load("/mail/layout.html");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private static final String PARAGRAPH = "<p style=\"margin:0 0 12px;font-size:15px;line-height:1.6;\">%s</p>\n";
    private static final String CELL = "padding:8px 0;border-bottom:1px solid #e5e7eb;";

    private EmailLayout() {
    }

    static String render(Content content, String recipientName) {
        Map<String, String> values = Map.of(
                "subject", escape(content.subject()),
                "store", escape(MessageTemplates.STORE),
                "name", escape(recipientName),
                "accent", accent(content.tone()),
                "content", body(content));
        // One pass over the layout, so text that happens to look like a placeholder is left alone.
        return PLACEHOLDER.matcher(LAYOUT)
                .replaceAll(match -> Matcher.quoteReplacement(values.get(match.group(1))));
    }

    private static String body(Content content) {
        StringBuilder html = new StringBuilder();
        content.paragraphs().forEach(paragraph -> html.append(PARAGRAPH.formatted(escape(paragraph))));
        if (!content.items().isEmpty()) {
            html.append(itemsTable(content));
        } else if (content.total() != null) {
            html.append(PARAGRAPH.formatted("<strong>Total: " + MessageTemplates.money(content.total()) + "</strong>"));
        }
        return html.toString();
    }

    private static String itemsTable(Content content) {
        StringBuilder table = new StringBuilder("""
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin:16px 0;border-collapse:collapse;font-size:14px;">
                <tr>
                <th align="left" style="padding:8px 0;border-bottom:2px solid #d1d5db;">Produto</th>
                <th align="center" style="padding:8px 0;border-bottom:2px solid #d1d5db;">Qtd.</th>
                <th align="right" style="padding:8px 0;border-bottom:2px solid #d1d5db;">Preço</th>
                </tr>
                """);
        for (Item item : content.items()) {
            table.append("<tr>\n")
                    .append("<td style=\"%s\">%s</td>\n".formatted(CELL, escape(item.name())))
                    .append("<td align=\"center\" style=\"%s\">%d</td>\n".formatted(CELL, item.quantity()))
                    .append("<td align=\"right\" style=\"%s\">%s</td>\n".formatted(CELL,
                            item.unitPrice() == null ? "" : MessageTemplates.money(item.unitPrice())))
                    .append("</tr>\n");
        }
        if (content.total() != null) {
            table.append("<tr>\n")
                    .append("<td colspan=\"2\" style=\"padding:12px 0 0;font-weight:bold;\">Total</td>\n")
                    .append("<td align=\"right\" style=\"padding:12px 0 0;font-weight:bold;\">%s</td>\n"
                            .formatted(MessageTemplates.money(content.total())))
                    .append("</tr>\n");
        }
        return table.append("</table>\n").toString();
    }

    private static String accent(Content.Tone tone) {
        return switch (tone) {
            case NEUTRAL -> "#1d4ed8";
            case GOOD_NEWS -> "#15803d";
            case BAD_NEWS -> "#b91c1c";
        };
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text, StandardCharsets.UTF_8.name());
    }

    private static String load(String path) {
        try (InputStream in = EmailLayout.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing e-mail layout " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
