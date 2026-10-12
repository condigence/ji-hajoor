package com.jihajoor.app;

import com.jihajoor.admin.AdminServiceApplication;
import com.jihajoor.complaint.ComplaintServiceApplication;
import com.jihajoor.host.HostedService;
import com.jihajoor.host.ServiceHost;
import com.jihajoor.notification.NotificationServiceApplication;
import com.jihajoor.outbox.OutboxProcessorApplication;
import com.jihajoor.rating.RatingReviewServiceApplication;
import com.jihajoor.reporting.ReportingServiceApplication;

/**
 * Engagement and operations: notifications, complaints, reviews, the admin back end, reporting and the outbox relay.
 *
 * <p>Runs 6 services in one JVM, each in its own Spring context on its own port; see
 * {@link ServiceHost}.
 */
public final class EngagementApplication {

    private EngagementApplication() {
    }

    public static void main(String[] args) {
        ServiceHost.run(args,
                HostedService.of("outbox-processor", OutboxProcessorApplication.class, 8099),
                HostedService.of("notification-service", NotificationServiceApplication.class, 8090),
                HostedService.of("complaint-service", ComplaintServiceApplication.class, 8091),
                HostedService.of("rating-review-service", RatingReviewServiceApplication.class, 8097),
                HostedService.of("admin-service", AdminServiceApplication.class, 8095),
                HostedService.of("reporting-service", ReportingServiceApplication.class, 8096));
    }
}
