package com.jihajoor.provider.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jihajoor.provider.config.InternalApiKeyFilter;
import com.jihajoor.provider.config.WebSecurityConfig;
import com.jihajoor.provider.domain.ProviderProfile;
import com.jihajoor.provider.domain.RatingUpdateOutcome;
import com.jihajoor.provider.service.ProviderException;
import com.jihajoor.provider.service.ProviderService;
import com.jihajoor.provider.service.ProviderService.RatingApplication;
import com.jihajoor.shared.security.JiHajoorSecurityAutoConfiguration;

/**
 * Web-layer tests for {@code PUT /internal/providers/{id}/rating} through the service's real
 * security chain: the Rating &amp; Review Service's aggregate reaches
 * {@link ProviderService#applyRatingAggregate}, out-of-range values are refused before it, and only
 * the shared {@code X-Internal-Api-Key} gets in.
 */
@WebMvcTest(controllers = InternalRatingController.class, properties = {
        "jihajoor.security.jwt-secret=" + InternalRatingControllerTest.JWT_SECRET,
        "jihajoor.provider.internal-api-key=" + InternalRatingControllerTest.INTERNAL_KEY
})
@Import({WebSecurityConfig.class, GlobalExceptionHandler.class})
@ImportAutoConfiguration(JiHajoorSecurityAutoConfiguration.class)
class InternalRatingControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";
    static final String INTERNAL_KEY = "test-internal-key";

    private static final UUID PROVIDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String BODY = "{\"average\":4.20,\"count\":12,\"sequence\":7}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProviderService providerService;

    private static RatingApplication held(String average, int count, long sequence,
                                          RatingUpdateOutcome outcome) {
        ProviderProfile profile = ProviderProfile.createWithId(PROVIDER);
        profile.applyRatingAggregate(new BigDecimal(average), count, sequence, BigDecimal.ZERO);
        return new RatingApplication(profile, outcome);
    }

    @Test
    void setsTheAggregateAndReturnsTheRatingHeld() throws Exception {
        when(providerService.applyRatingAggregate(eq(PROVIDER), eq(new BigDecimal("4.20")), eq(12), eq(7L)))
                .thenReturn(held("4.20", 12, 7, RatingUpdateOutcome.APPLIED));

        mockMvc.perform(put("/internal/providers/{id}/rating", PROVIDER)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value(PROVIDER.toString()))
                .andExpect(jsonPath("$.aggregateRating").value(4.20))
                .andExpect(jsonPath("$.ratingCount").value(12))
                .andExpect(jsonPath("$.ratingSequence").value(7))
                .andExpect(jsonPath("$.underReview").value(false))
                .andExpect(jsonPath("$.applied").value(true));
    }

    @Test
    void aStalePushIsA200ReportingTheNewerRating() throws Exception {
        when(providerService.applyRatingAggregate(any(), any(), anyInt(), anyLong()))
                .thenReturn(held("4.80", 13, 9, RatingUpdateOutcome.STALE));

        mockMvc.perform(put("/internal/providers/{id}/rating", PROVIDER)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.ratingSequence").value(9));
    }

    @Test
    void noCountedReviewsIsSentWithANullAverage() throws Exception {
        when(providerService.applyRatingAggregate(eq(PROVIDER), isNull(), eq(0), eq(3L)))
                .thenReturn(held("0", 0, 3, RatingUpdateOutcome.APPLIED));

        mockMvc.perform(put("/internal/providers/{id}/rating", PROVIDER)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"average\":null,\"count\":0,\"sequence\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratingCount").value(0));
    }

    @Test
    void unknownProviderIs404() throws Exception {
        when(providerService.applyRatingAggregate(any(), any(), anyInt(), anyLong()))
                .thenThrow(ProviderException.notFound("Provider " + PROVIDER + " not found"));

        mockMvc.perform(put("/internal/providers/{id}/rating", PROVIDER)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_NOT_FOUND"));
    }

    @Test
    void anAverageOutsideZeroToFiveIs400() throws Exception {
        for (String body : new String[] {
                "{\"average\":5.01,\"count\":1,\"sequence\":1}",
                "{\"average\":-0.5,\"count\":1,\"sequence\":1}",
                "{\"average\":4.123,\"count\":1,\"sequence\":1}"}) {
            mockMvc.perform(put("/internal/providers/{id}/rating", PROVIDER)
                            .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        }
        verify(providerService, never()).applyRatingAggregate(any(), any(), anyInt(), anyLong());
    }

    @Test
    void aMissingCountOrSequenceOrAnAverageThatDisagreesWithTheCountIs400() throws Exception {
        for (String body : new String[] {
                "{\"average\":4.0,\"sequence\":1}",
                "{\"average\":4.0,\"count\":1}",
                "{\"average\":4.0,\"count\":-1,\"sequence\":1}",
                "{\"average\":4.0,\"count\":1,\"sequence\":-1}",
                "{\"count\":2,\"sequence\":1}",
                "{\"average\":3.5,\"count\":0,\"sequence\":1}"}) {
            mockMvc.perform(put("/internal/providers/{id}/rating", PROVIDER)
                            .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(providerService, never()).applyRatingAggregate(any(), any(), anyInt(), anyLong());
    }

    @Test
    void withoutTheServiceCredentialNothingIsSet() throws Exception {
        mockMvc.perform(put("/internal/providers/{id}/rating", PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/internal/providers/{id}/rating", PROVIDER)
                        .header(InternalApiKeyFilter.HEADER, "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(providerService, never()).applyRatingAggregate(any(), any(), anyInt(), anyLong());
    }
}
