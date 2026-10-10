package io.github.jhonatasdefreitas.notifications.api;

class NotFoundException extends RuntimeException {

    NotFoundException(String message) {
        super(message);
    }
}
