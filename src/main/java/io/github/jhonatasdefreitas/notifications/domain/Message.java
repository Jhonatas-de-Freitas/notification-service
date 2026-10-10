package io.github.jhonatasdefreitas.notifications.domain;

/** A template already rendered for one channel: HTML for e-mail, plain text for SMS and push. */
public record Message(String subject, String body) {
}
