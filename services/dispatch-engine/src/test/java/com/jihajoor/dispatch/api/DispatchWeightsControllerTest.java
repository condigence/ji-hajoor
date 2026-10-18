package com.jihajoor.dispatch.api;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.jihajoor.dispatch.config.DispatchProperties;
import com.jihajoor.dispatch.domain.DispatchSettings;
import com.jihajoor.dispatch.domain.MatchingWeights;
import com.jihajoor.dispatch.service.DispatchSettingsService;
import com.jihajoor.dispatch.service.fake.InMemoryDispatchSettingsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Admin weight-update endpoint accepts a valid weight set and rejects an invalid one
 * with a descriptive 400, leaving the saved weights unchanged (Requirement 19.5, Property 19).
 * Uses a standalone MockMvc over an in-memory settings store so no database/Kafka/Redis context is
 * required.
 */
class DispatchWeightsControllerTest {

    private InMemoryDispatchSettingsStore store;
    private DispatchSettingsService settingsService;
    private MockMvc mockMvc;
    private final ObjectMapper mapper = new JsonMapper();

    @BeforeEach
    void setUp() {
        store = new InMemoryDispatchSettingsStore();
        settingsService = new DispatchSettingsService(store, new DispatchProperties(), Clock.systemUTC());
        DispatchWeightsController controller = new DispatchWeightsController(settingsService, new CallerIdentity());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(controller) // the @ExceptionHandler lives on the controller
                .build();
    }

    @Test
    void acceptsWeightsThatSumToOne() throws Exception {
        String body = """
                {"distanceWeight":0.40,"availabilityWeight":0.20,"ratingWeight":0.20,
                 "skillWeight":0.10,"performanceWeight":0.10}""";

        mockMvc.perform(put("/admin/dispatch/weights")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceWeight").value(0.40));

        assertThat(store.saved().orElseThrow().weights().distanceWeight()).isEqualTo(0.40);
    }

    @Test
    void updatingTheWeightsKeepsTheOtherSavedRules() throws Exception {
        DispatchSettings saved = new DispatchSettings(MatchingWeights.DEFAULT, 12.0, 3.0, 4, 90, 7, 10);
        store.saveElsewhere(saved);
        String body = """
                {"distanceWeight":0.40,"availabilityWeight":0.20,"ratingWeight":0.20,
                 "skillWeight":0.10,"performanceWeight":0.10}""";

        mockMvc.perform(put("/admin/dispatch/weights")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertThat(store.saved()).contains(saved.withWeights(MatchingWeights.of(0.40, 0.20, 0.20, 0.10, 0.10)));
    }

    @Test
    void getReturnsTheSavedWeights() throws Exception {
        store.saveElsewhere(new DispatchSettings(MatchingWeights.of(0.40, 0.20, 0.20, 0.10, 0.10),
                10.0, 5.0, 3, 60, 3, 5));

        mockMvc.perform(get("/admin/dispatch/weights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceWeight").value(0.40))
                .andExpect(jsonPath("$.performanceWeight").value(0.10));
    }

    @Test
    void rejectsWeightsThatDoNotSumToOneAndLeavesExistingUnchanged() throws Exception {
        MatchingWeights before = settingsService.current().weights();
        String body = """
                {"distanceWeight":0.40,"availabilityWeight":0.40,"ratingWeight":0.20,
                 "skillWeight":0.10,"performanceWeight":0.10}""";

        mockMvc.perform(put("/admin/dispatch/weights")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DISPATCH_WEIGHTS"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sum to exactly 1.0")));

        assertThat(settingsService.current().weights()).isEqualTo(before);
        assertThat(store.saved()).isEmpty();
    }

    @Test
    void rejectsWeightOutOfRange() throws Exception {
        String body = """
                {"distanceWeight":1.20,"availabilityWeight":-0.10,"ratingWeight":-0.10,
                 "skillWeight":0.0,"performanceWeight":0.0}""";

        mockMvc.perform(put("/admin/dispatch/weights")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("[0.0, 1.0]")));
    }
}
