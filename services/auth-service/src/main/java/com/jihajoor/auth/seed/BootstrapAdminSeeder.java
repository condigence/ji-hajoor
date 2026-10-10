package com.jihajoor.auth.seed;

import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.jihajoor.auth.domain.Role;
import com.jihajoor.auth.domain.UserAccount;
import com.jihajoor.auth.domain.UserAccountRepository;

/**
 * Creates the first platform administrator of a fresh deployment, so someone can sign in to the
 * Admin Portal and create every other staff account from there.
 *
 * <p>Staff roles cannot be self-registered, so a new database has nobody who can open the
 * portal. {@link DevAccountSeeder} fills that gap locally with ten shared-password test
 * accounts, which must never reach a real deployment. This seeder is the production-safe
 * counterpart: one named account, a password the operator chooses, and it only ever creates.
 *
 * <ul>
 *   <li>Does nothing unless {@code BOOTSTRAP_ADMIN_USERNAME} is set.</li>
 *   <li>Refuses a password shorter than 12 characters rather than create a weak admin.</li>
 *   <li>Never modifies an existing account: once the admin exists, changing or leaving the
 *       variables in place has no effect, so a restart can never reset a password or re-grant
 *       a role an operator has since removed. Remove the variables after the first sign-in.</li>
 * </ul>
 */
@Component
public class BootstrapAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminSeeder.class);

    static final int MIN_PASSWORD_LENGTH = 12;

    /** Same E.164 rule the OTP endpoints apply. */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String mobileNumber;

    public BootstrapAdminSeeder(UserAccountRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                @Value("${jihajoor.auth.bootstrap-admin.username:}") String username,
                                @Value("${jihajoor.auth.bootstrap-admin.password:}") String password,
                                @Value("${jihajoor.auth.bootstrap-admin.mobile:}") String mobileNumber) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        this.password = password == null ? "" : password;
        this.mobileNumber = mobileNumber == null ? "" : mobileNumber.trim();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isEmpty()) {
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            log.error("BOOTSTRAP ADMIN: not created. BOOTSTRAP_ADMIN_PASSWORD must be at least {} "
                    + "characters.", MIN_PASSWORD_LENGTH);
            return;
        }
        if (!E164.matcher(mobileNumber).matches()) {
            log.error("BOOTSTRAP ADMIN: not created. BOOTSTRAP_ADMIN_MOBILE must be an "
                    + "international number such as +919876543210.");
            return;
        }

        if (userRepository.findByUsername(username).isPresent()
                || userRepository.findByMobileNumber(mobileNumber).isPresent()) {
            log.info("BOOTSTRAP ADMIN: an account with username '{}' or that mobile number "
                    + "already exists; left unchanged. You can remove the BOOTSTRAP_ADMIN_* "
                    + "variables.", username);
            return;
        }

        UserAccount admin = UserAccount.createVerified(mobileNumber, Role.SUPER_ADMIN);
        admin.addRole(Role.ADMIN);
        admin.setCredentials(username, passwordEncoder.encode(password));
        userRepository.save(admin);
        log.warn("BOOTSTRAP ADMIN: created super admin '{}'. Sign in to the Admin Portal, then "
                + "remove the BOOTSTRAP_ADMIN_* variables.", username);
    }
}
