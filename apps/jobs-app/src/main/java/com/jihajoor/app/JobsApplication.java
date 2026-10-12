package com.jihajoor.app;

import com.jihajoor.booking.BookingServiceApplication;
import com.jihajoor.catalog.CatalogServiceApplication;
import com.jihajoor.chat.ChatServiceApplication;
import com.jihajoor.dispatch.DispatchEngineApplication;
import com.jihajoor.host.HostedService;
import com.jihajoor.host.ServiceHost;
import com.jihajoor.location.LocationServiceApplication;
import com.jihajoor.pricing.PricingEngineApplication;
import com.jihajoor.promotion.PromotionServiceApplication;

/**
 * Jobs: the service catalogue, prices and coupons, bookings, dispatch, live location and chat.
 *
 * <p>Runs 7 services in one JVM, each in its own Spring context on its own port; see
 * {@link ServiceHost}.
 */
public final class JobsApplication {

    private JobsApplication() {
    }

    public static void main(String[] args) {
        ServiceHost.run(args,
                HostedService.of("catalog-service", CatalogServiceApplication.class, 8085),
                HostedService.of("pricing-engine", PricingEngineApplication.class, 8086),
                HostedService.of("promotion-service", PromotionServiceApplication.class, 8098),
                HostedService.of("booking-service", BookingServiceApplication.class, 8084),
                HostedService.of("dispatch-engine", DispatchEngineApplication.class, 8087),
                HostedService.of("location-service", LocationServiceApplication.class, 8092),
                HostedService.of("chat-service", ChatServiceApplication.class, 8093));
    }
}
