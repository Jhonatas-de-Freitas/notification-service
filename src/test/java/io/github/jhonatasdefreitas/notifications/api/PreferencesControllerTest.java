package io.github.jhonatasdefreitas.notifications.api;

import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.jhonatasdefreitas.notifications.domain.Preferences;
import io.github.jhonatasdefreitas.notifications.persistence.PreferencesRepository;

@WebMvcTest(PreferencesController.class)
class PreferencesControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PreferencesRepository preferences;

    @Test
    void aUserWhoNeverConfiguredGetsEmailAndPush() throws Exception {
        when(preferences.findById("c-1001")).thenReturn(Optional.empty());

        mvc.perform(get("/preferences/c-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("c-1001"))
                .andExpect(jsonPath("$.email").value(true))
                .andExpect(jsonPath("$.sms").value(false))
                .andExpect(jsonPath("$.push").value(true));
    }

    @Test
    void showsWhatTheUserChose() throws Exception {
        when(preferences.findById("c-1001")).thenReturn(Optional.of(new Preferences("c-1001", false, true, false)));

        mvc.perform(get("/preferences/c-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(false))
                .andExpect(jsonPath("$.sms").value(true))
                .andExpect(jsonPath("$.push").value(false));
    }

    @Test
    void storesTheChoiceForTheUserInThePath() throws Exception {
        mvc.perform(put("/preferences/c-1001").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "userId": "someone-else", "email": true, "sms": true, "push": false }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("c-1001"))
                .andExpect(jsonPath("$.sms").value(true))
                .andExpect(jsonPath("$.push").value(false));

        verify(preferences).upsert("c-1001", true, true, false);
    }

    @Test
    void refusesAnUpdateThatLeavesAChannelOut() throws Exception {
        mvc.perform(put("/preferences/c-1001").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": true }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", contains("push", "sms")));
        verifyNoInteractions(preferences);
    }
}
