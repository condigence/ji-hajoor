package com.jihajoor.app;

import com.jihajoor.auth.AuthServiceApplication;
import com.jihajoor.customer.CustomerServiceApplication;
import com.jihajoor.host.HostedService;
import com.jihajoor.host.ServiceHost;
import com.jihajoor.provider.ProviderServiceApplication;
import com.jihajoor.verification.VerificationServiceApplication;

/**
 * Identity and people: sign-in, customers, providers and agencies, verification.
 *
 * <p>Runs 4 services in one JVM, each in its own Spring context on its own port; see
 * {@link ServiceHost}.
 */
public final class IdentityApplication {

    private IdentityApplication() {
    }

    public static void main(String[] args) {
        ServiceHost.run(args,
                HostedService.of("auth-service", AuthServiceApplication.class, 8081),
                HostedService.of("customer-service", CustomerServiceApplication.class, 8082),
                HostedService.of("provider-service", ProviderServiceApplication.class, 8083),
                HostedService.of("verification-service", VerificationServiceApplication.class, 8094));
    }
}
