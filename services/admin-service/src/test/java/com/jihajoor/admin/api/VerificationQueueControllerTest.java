package com.jihajoor.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.jihajoor.admin.rbac.AdminAuthorization;
import com.jihajoor.admin.verification.StubVerificationQueueAdapter;
import com.jihajoor.admin.verification.VerificationQueueService;

class VerificationQueueControllerTest {

    private static Authentication auth(String name, String role) {
        return new UsernamePasswordAuthenticationToken(name, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new VerificationQueueController(
                    new VerificationQueueService(new StubVerificationQueueAdapter()), new AdminAuthorization()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void staffWithTheModuleSeeTheQueue() throws Exception {
        mvc.perform(get("/admin/verification-queue").principal(auth(UUID.randomUUID().toString(), "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void otherRolesAreRefused() throws Exception {
        mvc.perform(get("/admin/verification-queue").principal(auth(UUID.randomUUID().toString(), "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theActorIdFallsBackToTheNilUuid() {
        UUID id = UUID.randomUUID();
        assertThat(AdminPrincipals.actorId(auth(id.toString(), "ADMIN"))).isEqualTo(id);
        assertThat(AdminPrincipals.actorId(auth("not-a-uuid", "ADMIN"))).isEqualTo(new UUID(0L, 0L));
        assertThat(AdminPrincipals.actorId(null)).isEqualTo(new UUID(0L, 0L));
    }
}
