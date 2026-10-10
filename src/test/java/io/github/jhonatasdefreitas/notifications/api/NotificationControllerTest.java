package io.github.jhonatasdefreitas.notifications.api;

import static io.github.jhonatasdefreitas.notifications.Fixtures.ANA;
import static io.github.jhonatasdefreitas.notifications.Fixtures.NOW;
import static io.github.jhonatasdefreitas.notifications.Fixtures.pending;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import io.github.jhonatasdefreitas.notifications.application.NotificationService;
import io.github.jhonatasdefreitas.notifications.application.NotificationService.Accepted;
import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.InvalidRequestException;
import io.github.jhonatasdefreitas.notifications.domain.NewNotification;
import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.domain.Recipient;
import io.github.jhonatasdefreitas.notifications.domain.Template;
import io.github.jhonatasdefreitas.notifications.persistence.NotificationRepository;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    private static final String VALID_REQUEST = """
            {
              "userId": "c-1001",
              "orderId": null,
              "recipient": { "name": "Ana Souza", "email": "ana@exemplo.com", "phone": "+5511999990001", "deviceToken": "device-abc" },
              "channels": ["EMAIL", "SMS"],
              "template": "GENERIC",
              "params": { "title": "Olá", "message": "Texto livre" },
              "scheduledAt": null
            }
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    NotificationService service;

    @MockitoBean
    NotificationRepository notifications;

    @Test
    void acceptsTheRequestAndAnswersWithOneEntryPerChannel() throws Exception {
        Notification email = withId(pending(Channel.EMAIL, ANA));
        UUID requestId = UUID.randomUUID();
        when(service.accept(any(), isNull())).thenReturn(new Accepted(requestId, List.of(email)));

        postNotification(VALID_REQUEST)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.notifications", hasSize(1)))
                .andExpect(jsonPath("$.notifications[0].id").value(email.getId().toString()))
                .andExpect(jsonPath("$.notifications[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.notifications[0].status").value("PENDING"));

        ArgumentCaptor<NewNotification> accepted = ArgumentCaptor.forClass(NewNotification.class);
        verify(service).accept(accepted.capture(), isNull());
        assertThat(accepted.getValue().userId()).isEqualTo("c-1001");
        assertThat(accepted.getValue().recipient()).isEqualTo(new Recipient("Ana Souza", "ana@exemplo.com", "+5511999990001", "device-abc"));
        assertThat(accepted.getValue().channels()).containsExactlyInAnyOrder(Channel.EMAIL, Channel.SMS);
        assertThat(accepted.getValue().template()).isEqualTo(Template.GENERIC);
        assertThat(accepted.getValue().params()).containsEntry("title", "Olá").containsEntry("message", "Texto livre");
    }

    @Test
    void handsTheIdempotencyKeyToTheService() throws Exception {
        when(service.accept(any(), eq("pedido-42"))).thenReturn(new Accepted(UUID.randomUUID(), List.of()));

        mvc.perform(post("/notifications").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST)
                        .header("Idempotency-Key", " pedido-42 "))
                .andExpect(status().isAccepted());

        verify(service).accept(any(), eq("pedido-42"));
    }

    @Test
    void channelsAreOptional() throws Exception {
        when(service.accept(any(), isNull())).thenReturn(new Accepted(UUID.randomUUID(), List.of()));

        postNotification("""
                { "userId": "c-1001", "recipient": { "name": "Ana Souza" }, "template": "GENERIC",
                  "params": { "title": "Olá", "message": "Texto livre" } }
                """).andExpect(status().isAccepted());

        ArgumentCaptor<NewNotification> accepted = ArgumentCaptor.forClass(NewNotification.class);
        verify(service).accept(accepted.capture(), isNull());
        assertThat(accepted.getValue().channels()).isNull();
    }

    @Test
    void refusesARequestWithoutTheRequiredFields() throws Exception {
        postNotification("{}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("The request has invalid fields"))
                .andExpect(jsonPath("$.instance").value("/notifications"))
                .andExpect(jsonPath("$.errors[*].field", contains("recipient", "template", "userId")))
                .andExpect(jsonPath("$.errors[0].message").value("is required"));
        verifyNoInteractions(service);
    }

    @Test
    void refusesAnInvalidAddress() throws Exception {
        postNotification("""
                { "userId": "c-1001", "template": "GENERIC",
                  "recipient": { "name": "Ana Souza", "email": "not-an-email", "phone": "12345" } }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("recipient.email", "recipient.phone")));
    }

    @Test
    void refusesAnEmptyChannelList() throws Exception {
        postNotification("""
                { "userId": "c-1001", "template": "GENERIC", "recipient": { "name": "Ana Souza" }, "channels": [] }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("channels")));
    }

    @Test
    void refusesAChannelThatDoesNotExist() throws Exception {
        postNotification("""
                { "userId": "c-1001", "template": "GENERIC", "recipient": { "name": "Ana Souza" }, "channels": ["FAX"] }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("The request has invalid fields"))
                .andExpect(jsonPath("$.instance").value("/notifications"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("channels[0]"))
                .andExpect(jsonPath("$.errors[0].message").value("must be one of EMAIL, SMS, PUSH"));
        verifyNoInteractions(service);
    }

    @Test
    void refusesATemplateThatDoesNotExist() throws Exception {
        postNotification("""
                { "userId": "c-1001", "template": "ORDER_SHIPPED", "recipient": { "name": "Ana Souza" } }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("template"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("must be one of ORDER_RECEIVED, ORDER_CONFIRMED, ORDER_CANCELLED, GENERIC"));
    }

    @Test
    void refusesAValueOfTheWrongTypeNamingItsField() throws Exception {
        postNotification("""
                { "userId": "c-1001", "template": "GENERIC", "recipient": { "name": "Ana Souza" },
                  "scheduledAt": "tomorrow" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("scheduledAt"))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid format"));
    }

    @Test
    void refusesABodyThatIsNotJsonWithoutBlamingAField() throws Exception {
        postNotification("{ not json")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void reportsWhatTheTemplateIsMissing() throws Exception {
        when(service.accept(any(), any()))
                .thenThrow(new InvalidRequestException("params.message", "is required for template GENERIC"));

        postNotification(VALID_REQUEST)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("params.message"))
                .andExpect(jsonPath("$.errors[0].message").value("is required for template GENERIC"));
    }

    @Test
    void showsANotificationWithItsAttempts() throws Exception {
        Notification sms = withId(pending(Channel.SMS, ANA));
        sms.recordFailure(NOW.plusSeconds(1), "Operadora indisponível", true);
        sms.recordSuccess(NOW.plusSeconds(6));
        when(notifications.findById(sms.getId())).thenReturn(Optional.of(sms));

        mvc.perform(get("/notifications/{id}", sms.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sms.getId().toString()))
                .andExpect(jsonPath("$.userId").value("c-1001"))
                .andExpect(jsonPath("$.orderId").isEmpty())
                .andExpect(jsonPath("$.channel").value("SMS"))
                .andExpect(jsonPath("$.template").value("GENERIC"))
                .andExpect(jsonPath("$.recipient.name").value("Ana Souza"))
                .andExpect(jsonPath("$.recipient.phone").value("+5511999990002"))
                .andExpect(jsonPath("$.subject").value("Olá"))
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.attempts", hasSize(2)))
                .andExpect(jsonPath("$.attempts[0].number").value(1))
                .andExpect(jsonPath("$.attempts[0].at").value("2026-10-10T14:00:01Z"))
                .andExpect(jsonPath("$.attempts[0].success").value(false))
                .andExpect(jsonPath("$.attempts[0].error").value("Operadora indisponível"))
                .andExpect(jsonPath("$.attempts[1].success").value(true))
                .andExpect(jsonPath("$.attempts[1].error").isEmpty())
                .andExpect(jsonPath("$.scheduledAt").isEmpty())
                .andExpect(jsonPath("$.sentAt").value("2026-10-10T14:00:06Z"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-10T14:00:00Z"))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void answers404ForANotificationThatDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        when(notifications.findById(id)).thenReturn(Optional.empty());

        mvc.perform(get("/notifications/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Notification " + id + " not found"))
                .andExpect(jsonPath("$.instance").value("/notifications/" + id));
    }

    @Test
    void listsAPageInTheAgreedShape() throws Exception {
        Notification email = withId(pending(Channel.EMAIL, ANA));
        when(notifications.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<>(List.of(email), call.getArgument(1), 41));

        mvc.perform(get("/notifications").param("userId", "c-1001").param("page", "2").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(email.getId().toString()))
                .andExpect(jsonPath("$.content[0].attempts", hasSize(0)))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(41))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void capsThePageSize() throws Exception {
        when(notifications.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<>(List.of(), call.getArgument(1), 0));

        mvc.perform(get("/notifications").param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void refusesAStatusFilterThatDoesNotExist() throws Exception {
        mvc.perform(get("/notifications").param("status", "LOST"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.instance").value("/notifications"))
                .andExpect(jsonPath("$.errors[0].field").value("status"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("must be one of SCHEDULED, PENDING, RETRYING, SENT, FAILED, SKIPPED"));
    }

    @Test
    void refusesAnIdThatIsNotAUuid() throws Exception {
        mvc.perform(get("/notifications/{id}", "42"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("id"))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid format"));
    }

    @Test
    void refusesAnIdempotencyKeyTooLongToStore() throws Exception {
        mvc.perform(post("/notifications").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST)
                        .header("Idempotency-Key", "k".repeat(256)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("Idempotency-Key")));
    }

    private ResultActions postNotification(String body) throws Exception {
        return mvc.perform(post("/notifications").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static Notification withId(Notification notification) {
        ReflectionTestUtils.setField(notification, "id", UUID.randomUUID());
        return notification;
    }
}
