package com.jihajoor.provider.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jihajoor.provider.config.ProviderRbacConfig;
import com.jihajoor.provider.config.WebSecurityConfig;
import com.jihajoor.provider.service.AgencyInvitationService;
import com.jihajoor.provider.service.AgencyInvitationService.AgencyRef;
import com.jihajoor.provider.service.AgencyInvitationService.WorkerAgencyView;
import com.jihajoor.shared.security.JiHajoorSecurityAutoConfiguration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * The worker's own agency endpoints: only the worker, acting on their own record (the JWT
 * subject), whatever id a request carries; staff are refused, since joining or leaving an agency
 * is the worker's decision.
 */
@WebMvcTest(controllers = ProviderAgencyController.class, properties = {
        "jihajoor.security.jwt-secret=" + ProviderAgencyControllerTest.JWT_SECRET,
        "jihajoor.provider.internal-api-key=unused-internal-key"
})
@Import({WebSecurityConfig.class, ProviderRbacConfig.class, CallerIdentity.class,
        GlobalExceptionHandler.class})
@ImportAutoConfiguration(JiHajoorSecurityAutoConfiguration.class)
class ProviderAgencyControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";

    private final UUID worker = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgencyInvitationService invitations;

    private String token(UUID subject, String... roles) {
        return "Bearer " + Jwts.builder()
                .subject(subject.toString())
                .claim("roles", List.of(roles))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void theWorkerReadsAndAnswersTheirOwnInvitations() throws Exception {
        UUID invitation = UUID.randomUUID();
        UUID agency = UUID.randomUUID();
        WorkerAgencyView joined = new WorkerAgencyView(new AgencyRef(agency, "Ara Home Services",
                new BigDecimal("10.00")), List.of());
        when(invitations.forWorker(worker)).thenReturn(new WorkerAgencyView(null, List.of()));
        when(invitations.accept(worker, invitation)).thenReturn(joined);
        String auth = token(worker, "SERVICE_PROVIDER");

        mockMvc.perform(get("/providers/me/agency").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mockMvc.perform(post("/providers/me/agency/invitations/" + invitation + "/accept")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agency.name").value("Ara Home Services"));
        mockMvc.perform(post("/providers/me/agency/invitations/" + invitation + "/decline")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mockMvc.perform(post("/providers/me/agency/leave").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());

        verify(invitations).decline(worker, invitation);
        verify(invitations).leave(worker);
    }

    @Test
    void staffAgencyAdminsAndAnonymousCallersAreRefused() throws Exception {
        UUID invitation = UUID.randomUUID();
        for (String role : List.of("ADMIN", "SUPER_ADMIN", "TENANT_ADMIN", "CUSTOMER")) {
            String auth = token(UUID.randomUUID(), role);
            mockMvc.perform(get("/providers/me/agency").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/providers/me/agency/invitations/" + invitation + "/accept")
                            .header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/providers/me/agency/leave")).andExpect(status().isUnauthorized());
        verifyNoInteractions(invitations);
    }
}
