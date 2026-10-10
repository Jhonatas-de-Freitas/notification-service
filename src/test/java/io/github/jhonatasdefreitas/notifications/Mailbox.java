package io.github.jhonatasdefreitas.notifications;

import java.util.List;

import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

class Mailbox {

    private final RestClient http;

    Mailbox(String mailpitUrl) {
        this.http = RestClient.create(mailpitUrl);
    }

    /** Summaries (Subject, To, Snippet) of the messages delivered to the address. */
    List<JsonNode> messagesTo(String address) {
        JsonNode result = http.get().uri("/api/v1/search?query={query}", "to:" + address)
                .retrieve()
                .body(JsonNode.class);
        return result.get("messages").valueStream().toList();
    }

    String htmlOf(JsonNode message) {
        return http.get().uri("/api/v1/message/{id}", message.get("ID").asString())
                .retrieve()
                .body(JsonNode.class)
                .get("HTML").asString();
    }
}
