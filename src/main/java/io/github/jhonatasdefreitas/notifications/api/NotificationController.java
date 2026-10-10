package io.github.jhonatasdefreitas.notifications.api;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.jhonatasdefreitas.notifications.application.NotificationService;
import io.github.jhonatasdefreitas.notifications.domain.InvalidRequestException;
import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.domain.NotificationStatus;
import io.github.jhonatasdefreitas.notifications.persistence.NotificationRepository;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/notifications")
class NotificationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private static final int MAX_KEY_LENGTH = 255;
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationService service;
    private final NotificationRepository notifications;

    NotificationController(NotificationService service, NotificationRepository notifications) {
        this.service = service;
        this.notifications = notifications;
    }

    // 202: the notifications are stored and queued, the delivery happens later.
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    AcceptedResponse send(@Valid @RequestBody SendNotificationRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        String key = blankToNull(idempotencyKey);
        if (key != null && key.length() > MAX_KEY_LENGTH) {
            throw new InvalidRequestException(IDEMPOTENCY_KEY, "must have at most " + MAX_KEY_LENGTH + " characters");
        }
        return AcceptedResponse.of(service.accept(request.toDomain(), key));
    }

    @GetMapping
    PageResponse<NotificationResponse> list(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        // "?userId=" arrives as an empty text, not as a missing parameter. It must not filter either.
        Page<Notification> found = notifications.findAll(
                NotificationRepository.matching(blankToNull(userId), orderId, status), pageRequest);
        return PageResponse.of(found, NotificationResponse::of);
    }

    @GetMapping("/{id}")
    NotificationResponse get(@PathVariable UUID id) {
        return notifications.findById(id)
                .map(NotificationResponse::of)
                .orElseThrow(() -> new NotFoundException("Notification " + id + " not found"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
