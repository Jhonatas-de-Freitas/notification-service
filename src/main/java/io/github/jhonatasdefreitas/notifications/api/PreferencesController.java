package io.github.jhonatasdefreitas.notifications.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.jhonatasdefreitas.notifications.domain.InvalidRequestException;
import io.github.jhonatasdefreitas.notifications.domain.Preferences;
import io.github.jhonatasdefreitas.notifications.persistence.PreferencesRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/preferences/{userId}")
class PreferencesController {

    private static final int MAX_USER_ID_LENGTH = 100;

    record PreferencesBody(
            String userId,
            @NotNull(message = "is required") Boolean email,
            @NotNull(message = "is required") Boolean sms,
            @NotNull(message = "is required") Boolean push) {

        static PreferencesBody of(Preferences preferences) {
            return new PreferencesBody(preferences.getUserId(), preferences.isEmail(), preferences.isSms(),
                    preferences.isPush());
        }
    }

    private final PreferencesRepository preferences;

    PreferencesController(PreferencesRepository preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    PreferencesBody get(@PathVariable String userId) {
        return PreferencesBody.of(preferences.findById(userId).orElseGet(() -> Preferences.defaultsFor(userId)));
    }

    // The user is the one in the path; a userId in the body is ignored.
    @PutMapping
    PreferencesBody update(@PathVariable String userId, @Valid @RequestBody PreferencesBody body) {
        if (userId.length() > MAX_USER_ID_LENGTH) {
            throw new InvalidRequestException("userId", "must have at most " + MAX_USER_ID_LENGTH + " characters");
        }
        preferences.upsert(userId, body.email(), body.sms(), body.push());
        return new PreferencesBody(userId, body.email(), body.sms(), body.push());
    }
}
