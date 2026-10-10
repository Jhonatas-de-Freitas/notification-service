package io.github.jhonatasdefreitas.notifications.domain;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.github.jhonatasdefreitas.notifications.domain.Content.Item;
import io.github.jhonatasdefreitas.notifications.domain.Content.Tone;
import io.github.jhonatasdefreitas.notifications.domain.InvalidRequestException.Violation;

/**
 * The texts of each template. The parameters of the order templates have the same names as the
 * payload of the order events, and whatever is missing is left out of the message instead of
 * refusing it: a shorter notice is better than none.
 */
public final class MessageTemplates {

    static final String STORE = "Loja Exemplo";

    private static final int MAX_TITLE_LENGTH = 150;
    private static final int MAX_MESSAGE_LENGTH = 2000;

    private MessageTemplates() {
    }

    public static Content compose(Template template, UUID orderId, Map<String, Object> params) {
        if (template != Template.GENERIC && orderId == null) {
            throw new InvalidRequestException("orderId", "is required for template " + template);
        }
        return switch (template) {
            case ORDER_RECEIVED -> orderReceived(orderNumber(orderId), params);
            case ORDER_CONFIRMED -> orderConfirmed(orderNumber(orderId), params);
            case ORDER_CANCELLED -> orderCancelled(orderNumber(orderId), params);
            case GENERIC -> generic(params);
        };
    }

    private static Content orderReceived(String order, Map<String, Object> params) {
        int quantity = items(params).stream().mapToInt(Item::quantity).sum();
        String size = quantity == 0 ? "" : quantity == 1 ? " com 1 item" : " com " + quantity + " itens";
        return new Content(
                "Recebemos o seu pedido " + order,
                Tone.NEUTRAL,
                List.of("Recebemos o seu pedido " + order + size + ".",
                        "Agora vamos reservar os itens e processar o pagamento. "
                                + "Você recebe outra mensagem assim que o pedido for confirmado."),
                List.of(),
                null,
                "Recebemos o seu pedido " + order + ". Avisamos assim que ele for confirmado.");
    }

    private static Content orderConfirmed(String order, Map<String, Object> params) {
        BigDecimal total = decimal(params.get("totalAmount"));
        return new Content(
                "Seu pedido " + order + " foi confirmado",
                Tone.GOOD_NEWS,
                List.of("O pagamento foi aprovado e o seu pedido " + order + " está confirmado."),
                items(params),
                total,
                "Seu pedido " + order + " foi confirmado." + (total == null ? "" : " Total: " + money(total) + "."));
    }

    private static Content orderCancelled(String order, Map<String, Object> params) {
        String reason = switch (String.valueOf(params.get("reason"))) {
            case "OUT_OF_STOCK" -> "um ou mais itens estão sem estoque";
            case "PAYMENT_REFUSED" -> "o pagamento não foi aprovado";
            default -> null;
        };
        String detail = text(params.get("detail"));

        List<String> paragraphs = new ArrayList<>();
        paragraphs.add("Não foi possível concluir o seu pedido " + order + ", e ele foi cancelado.");
        if (reason != null) {
            paragraphs.add("Motivo: " + reason + ".");
        }
        if (detail != null) {
            paragraphs.add("Detalhe: " + detail);
        }
        paragraphs.add("Nenhum valor foi cobrado.");
        return new Content(
                "Seu pedido " + order + " foi cancelado",
                Tone.BAD_NEWS,
                paragraphs,
                List.of(),
                null,
                "Seu pedido " + order + " foi cancelado" + (reason == null ? "." : ": " + reason + "."));
    }

    private static Content generic(Map<String, Object> params) {
        String title = text(params.get("title"));
        String message = text(params.get("message"));
        List<Violation> violations = new ArrayList<>();
        if (title == null) {
            violations.add(new Violation("params.title", "is required for template GENERIC"));
        } else if (title.length() > MAX_TITLE_LENGTH) {
            violations.add(new Violation("params.title", "must have at most " + MAX_TITLE_LENGTH + " characters"));
        }
        if (message == null) {
            violations.add(new Violation("params.message", "is required for template GENERIC"));
        } else if (message.length() > MAX_MESSAGE_LENGTH) {
            violations.add(new Violation("params.message", "must have at most " + MAX_MESSAGE_LENGTH + " characters"));
        }
        if (!violations.isEmpty()) {
            throw new InvalidRequestException(violations);
        }
        List<String> paragraphs = message.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        return new Content(title, Tone.NEUTRAL, paragraphs, List.of(), null, String.join(" ", paragraphs));
    }

    /** The first block of the id is enough for a customer to tell orders apart, and fits in an SMS. */
    private static String orderNumber(UUID orderId) {
        return "#" + orderId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    static String money(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        return "R$ " + format.format(value);
    }

    private static List<Item> items(Map<String, Object> params) {
        if (!(params.get("items") instanceof List<?> rawItems)) {
            return List.of();
        }
        List<Item> items = new ArrayList<>();
        for (Object rawItem : rawItems) {
            if (rawItem instanceof Map<?, ?> item && item.get("quantity") instanceof Number quantity) {
                String name = text(item.get("productName"));
                items.add(new Item(name == null ? "Produto" : name, quantity.intValue(), decimal(item.get("unitPrice"))));
            }
        }
        return items;
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof Number number ? new BigDecimal(number.toString()) : null;
    }
}
