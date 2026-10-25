package com.jihajoor.location.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jihajoor.location.config.LocationRbacConfig;
import com.jihajoor.location.config.WebSecurityConfig;
import com.jihajoor.location.domain.Coordinates;
import com.jihajoor.location.service.LocationAccessPolicy;
import com.jihajoor.location.service.LocationException;
import com.jihajoor.location.service.LocationService;
import com.jihajoor.location.service.LocationUpdateCommand;
import com.jihajoor.location.service.TrackingView;
import com.jihajoor.location.subscription.LocationUpdatePush;
import com.jihajoor.location.subscription.SubscriberRegistryPort;
import com.jihajoor.shared.security.JiHajoorSecurityAutoConfiguration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * The Location endpoints through the real security chain: role rules, the provider taken from
 * the token rather than the body, ownership checks before any read, and error mapping.
 */
@WebMvcTest(properties = "jihajoor.security.jwt-secret=" + LocationControllerTest.JWT_SECRET)
@ContextConfiguration(classes = {LocationController.class, GlobalExceptionHandler.class,
        WebSecurityConfig.class, LocationRbacConfig.class})
@ImportAutoConfiguration(JiHajoorSecurityAutoConfiguration.class)
class LocationControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";

    private final UUID booking = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocationService locationService;
    @MockitoBean
    private SubscriberRegistryPort subscriberRegistry;
    @MockitoBean
    private LocationAccessPolicy accessPolicy;

    private static String token(UUID subject, String... roles) {
        return "Bearer " + Jwts.builder()
                .subject(subject.toString())
                .claim("roles", List.of(roles))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void theAssignedProviderPostsAPosition_inTheirOwnNameWhateverTheBodySays() throws Exception {
        when(locationService.ingest(any())).thenReturn(new LocationUpdatePush(new Coordinates(25.5, 84.6), 7));
        String someoneElse = UUID.randomUUID().toString();

        mockMvc.perform(post("/locations/" + booking)
                        .header(HttpHeaders.AUTHORIZATION, token(provider, "SERVICE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"" + someoneElse + "\",\"latitude\":25.5,\"longitude\":84.6}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.latitude").value(25.5))
                .andExpect(jsonPath("$.longitude").value(84.6))
                .andExpect(jsonPath("$.etaMinutes").value(7));

        verify(accessPolicy).requireAssignedProvider(booking, provider);
        verify(locationService).ingest(new LocationUpdateCommand(booking, provider, new Coordinates(25.5, 84.6)));
    }

    @Test
    void anOutOfRangePositionIsAValidationError() throws Exception {
        mockMvc.perform(post("/locations/" + booking)
                        .header(HttpHeaders.AUTHORIZATION, token(provider, "SERVICE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":91,\"longitude\":200}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.length()").value(2));
        verify(locationService, never()).ingest(any());
    }

    @Test
    void aCustomerCannotPostPositions_andAnonymousCallersAreRefused() throws Exception {
        mockMvc.perform(post("/locations/" + booking)
                        .header(HttpHeaders.AUTHORIZATION, token(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":25.5,\"longitude\":84.6}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/locations/" + booking)).andExpect(status().isUnauthorized());
        verify(locationService, never()).ingest(any());
    }

    @Test
    void theCustomerReadsTheTrackingView() throws Exception {
        Instant at = Instant.parse("2026-10-25T10:00:00Z");
        when(locationService.trackingView(booking))
                .thenReturn(new TrackingView(booking, provider, new Coordinates(25.5, 84.6), at, true));

        mockMvc.perform(get("/locations/" + booking).header(HttpHeaders.AUTHORIZATION, token(customer, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(booking.toString()))
                .andExpect(jsonPath("$.providerId").value(provider.toString()))
                .andExpect(jsonPath("$.stale").value(true));

        verify(accessPolicy).requireReadAccess(eq(booking), eq(customer), anyCollection());
    }

    @Test
    void someoneElsesBookingIsNotFound_withTheDomainErrorCode() throws Exception {
        doThrow(LocationException.bookingNotFound("no such booking"))
                .when(accessPolicy).requireReadAccess(eq(booking), eq(customer), anyCollection());

        mockMvc.perform(get("/locations/" + booking).header(HttpHeaders.AUTHORIZATION, token(customer, "CUSTOMER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").exists());
        verify(locationService, never()).trackingView(any());
    }

    @Test
    void theStreamRegistersASubscriberAfterTheOwnershipCheck() throws Exception {
        mockMvc.perform(get("/locations/" + booking + "/stream")
                        .header(HttpHeaders.AUTHORIZATION, token(customer, "CUSTOMER")))
                .andExpect(request().asyncStarted());

        verify(accessPolicy).requireReadAccess(eq(booking), eq(customer), anyCollection());
        verify(subscriberRegistry).register(eq(booking), any());
    }

    @Test
    void aTokenWhoseSubjectIsNotAUserIdIsUnauthorized() throws Exception {
        String bad = "Bearer " + Jwts.builder()
                .subject("not-a-uuid")
                .claim("roles", List.of("CUSTOMER"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        mockMvc.perform(get("/locations/" + booking).header(HttpHeaders.AUTHORIZATION, bad))
                .andExpect(status().isUnauthorized());
    }
}
