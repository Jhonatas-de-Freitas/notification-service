package io.github.jhonatasdefreitas.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.jhonatasdefreitas.notifications.domain.InvalidRequestException.Violation;

class MessageTemplatesTest {

    private static final UUID ORDER_ID = UUID.fromString("7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c");

    private static final Map<String, Object> CONFIRMED = Map.of(
            "totalAmount", 7848.80,
            "items", List.of(
                    Map.of("productName", "Teclado Mecânico", "quantity", 2, "unitPrice", 349.90),
                    Map.of("productName", "Notebook Pro 16", "quantity", 1, "unitPrice", 7149.00)));

    @Test
    void orderReceivedSaysTheOrderNumberAndHowManyItems() {
        Map<String, Object> params = Map.of("items", List.of(
                Map.of("productId", "11111111-1111-1111-1111-111111111111", "quantity", 2),
                Map.of("productId", "22222222-2222-2222-2222-222222222222", "quantity", 1)));

        Content content = MessageTemplates.compose(Template.ORDER_RECEIVED, ORDER_ID, params);

        assertThat(content.subject()).isEqualTo("Recebemos o seu pedido #7C1F0A34");
        assertThat(content.paragraphs().getFirst()).isEqualTo("Recebemos o seu pedido #7C1F0A34 com 3 itens.");
    }

    @Test
    void orderConfirmedListsItemsAndTotalInTheEmail() {
        Message email = MessageTemplates.compose(Template.ORDER_CONFIRMED, ORDER_ID, CONFIRMED)
                .forChannel(Channel.EMAIL, "Ana Souza");

        assertThat(email.subject()).isEqualTo("Seu pedido #7C1F0A34 foi confirmado");
        assertThat(email.body())
                .startsWith("<!DOCTYPE html>")
                .contains("Olá, Ana Souza!")
                .contains("Teclado Mecânico", "R$ 349,90", "Notebook Pro 16", "R$ 7.149,00")
                .contains("R$ 7.848,80")
                .doesNotContain("{{");
    }

    @Test
    void orderConfirmedFitsInAnSms() {
        Message sms = MessageTemplates.compose(Template.ORDER_CONFIRMED, ORDER_ID, CONFIRMED)
                .forChannel(Channel.SMS, "Ana Souza");

        assertThat(sms.body()).isEqualTo("Loja Exemplo: Seu pedido #7C1F0A34 foi confirmado. Total: R$ 7.848,80.");
    }

    @Test
    void orderConfirmedStillWorksWithoutItemsOrTotal() {
        Content content = MessageTemplates.compose(Template.ORDER_CONFIRMED, ORDER_ID, Map.of());

        assertThat(content.items()).isEmpty();
        assertThat(content.summary()).isEqualTo("Seu pedido #7C1F0A34 foi confirmado.");
    }

    @Test
    void orderCancelledExplainsTheReason() {
        Content outOfStock = MessageTemplates.compose(Template.ORDER_CANCELLED, ORDER_ID,
                Map.of("reason", "OUT_OF_STOCK", "detail", "Headset USB: pedido 1, disponível 0"));
        Content refused = MessageTemplates.compose(Template.ORDER_CANCELLED, ORDER_ID,
                Map.of("reason", "PAYMENT_REFUSED"));

        assertThat(outOfStock.subject()).isEqualTo("Seu pedido #7C1F0A34 foi cancelado");
        assertThat(outOfStock.paragraphs()).contains(
                "Motivo: um ou mais itens estão sem estoque.",
                "Detalhe: Headset USB: pedido 1, disponível 0");
        assertThat(refused.summary()).isEqualTo("Seu pedido #7C1F0A34 foi cancelado: o pagamento não foi aprovado.");
    }

    @Test
    void orderCancelledLeavesAnUnknownReasonOut() {
        Content content = MessageTemplates.compose(Template.ORDER_CANCELLED, ORDER_ID, Map.of("reason", "SOMETHING_NEW"));

        assertThat(content.paragraphs()).noneMatch(paragraph -> paragraph.startsWith("Motivo"));
        assertThat(content.summary()).isEqualTo("Seu pedido #7C1F0A34 foi cancelado.");
    }

    @Test
    void orderTemplatesNeedTheOrder() {
        assertThatThrownBy(() -> MessageTemplates.compose(Template.ORDER_CONFIRMED, null, CONFIRMED))
                .asInstanceOf(type(InvalidRequestException.class))
                .extracting(InvalidRequestException::getViolations)
                .isEqualTo(List.of(new Violation("orderId", "is required for template ORDER_CONFIRMED")));
    }

    @Test
    void genericUsesTheTitleAsSubjectAndTheMessageAsText() {
        Content content = MessageTemplates.compose(Template.GENERIC, null,
                Map.of("title", "Olá", "message", "Primeira linha\n\nSegunda linha"));

        assertThat(content.subject()).isEqualTo("Olá");
        assertThat(content.paragraphs()).containsExactly("Primeira linha", "Segunda linha");
        assertThat(content.forChannel(Channel.PUSH, "Ana").body()).isEqualTo("Primeira linha Segunda linha");
    }

    @Test
    void genericNeedsTitleAndMessage() {
        assertThatThrownBy(() -> MessageTemplates.compose(Template.GENERIC, null, Map.of("title", " ")))
                .asInstanceOf(type(InvalidRequestException.class))
                .extracting(InvalidRequestException::getViolations)
                .asInstanceOf(list(Violation.class))
                .extracting(Violation::field)
                .containsExactly("params.title", "params.message");
    }

    @Test
    void emailEscapesWhatTheCallerSent() {
        Message email = MessageTemplates.compose(Template.GENERIC, null,
                Map.of("title", "Promoção <b>hoje</b>", "message", "<script>alert(1)</script> {{content}}"))
                .forChannel(Channel.EMAIL, "Ana & Cia");

        assertThat(email.body())
                .contains("Promoção &lt;b&gt;hoje&lt;/b&gt;")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt; {{content}}")
                .contains("Olá, Ana &amp; Cia!")
                .doesNotContain("<script>");
    }
}
