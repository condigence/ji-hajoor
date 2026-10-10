package com.jihajoor.auth.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.jihajoor.auth.domain.Role;
import com.jihajoor.auth.domain.UserAccount;
import com.jihajoor.auth.domain.UserAccountRepository;

/**
 * Unit tests for {@link BootstrapAdminSeeder}: creates exactly one super admin when configured,
 * and otherwise never touches the database or an existing account.
 */
@ExtendWith(MockitoExtension.class)
class BootstrapAdminSeederTest {

    private static final String STRONG = "correct-horse-battery";

    @Mock
    private UserAccountRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private void run(String username, String password, String mobile) {
        new BootstrapAdminSeeder(userRepository, passwordEncoder, username, password, mobile)
                .run(new DefaultApplicationArguments());
    }

    @Test
    void createsSuperAdmin_withNormalisedUsername() {
        when(userRepository.findByUsername("owner")).thenReturn(Optional.empty());
        when(userRepository.findByMobileNumber("+919876543210")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(STRONG)).thenReturn("$2a$12$hash");

        run("  Owner ", STRONG, "+919876543210");

        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(userRepository).save(saved.capture());
        UserAccount admin = saved.getValue();
        assertThat(admin.getUsername()).isEqualTo("owner");
        assertThat(admin.getMobileNumber()).isEqualTo("+919876543210");
        assertThat(admin.getPasswordHash()).isEqualTo("$2a$12$hash");
        assertThat(admin.getRoles()).containsExactlyInAnyOrder(Role.SUPER_ADMIN, Role.ADMIN);
    }

    @Test
    void doesNothing_whenNotConfigured() {
        run("", "", "");

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void refusesShortPassword() {
        run("owner", "short", "+919876543210");

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void refusesInvalidMobile() {
        run("owner", STRONG, "98765");

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void leavesExistingAccountUntouched() {
        UserAccount existing = UserAccount.createVerified("+919876543210", Role.CUSTOMER);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(existing));

        run("owner", STRONG, "+919876543210");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
        assertThat(existing.getRoles()).containsExactly(Role.CUSTOMER);
    }
}
