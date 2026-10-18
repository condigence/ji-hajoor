package com.jihajoor.complaint.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Tunable complaint limits, SLAs, and topic names (Requirement 16).
 *
 * <p>Defaults match the acceptance criteria; every value is overridable via configuration so
 * operations can tune limits without a code change.
 */
@ConfigurationProperties(prefix = "jihajoor.complaint")
public class ComplaintProperties {

    /** Maximum length of the complaint description (Requirement 16.1). */
    private int maxDescriptionLength = 2000;

    /** Customer acknowledgment SLA from submission (Requirement 16.2). */
    private Duration acknowledgmentSla = Duration.ofMinutes(30);

    /** In-app status-change notification SLA (Requirement 16.3). */
    private Duration statusChangeNotificationSla = Duration.ofMinutes(5);

    /** Resolution SLA for emergency service complaints (Requirement 16.4). */
    private Duration emergencyResolutionSla = Duration.ofHours(24);

    /** Resolution SLA for standard complaints (Requirement 16.4). */
    private Duration standardResolutionSla = Duration.ofHours(72);

    /** Maximum staleness of aggregated complaint stats for Admin reports (Requirement 16.9). */
    private Duration statsRefreshWindow = Duration.ofMinutes(60);

    /**
     * How long a refund call to the Payment Service with no recorded outcome counts as still in
     * flight (Requirement 16.5). Within it, a same-key retry is answered 409 REFUND_IN_PROGRESS so two
     * attempts of one refund never overlap at the gateway; after it, the attempt is presumed lost
     * (for example the instance died mid-call) and the retry re-sends the refund under the same key.
     * An attempt that ended with an unknown outcome can be re-sent at once. Must be comfortably
     * longer than the refund client's connect + read timeout ({@code jihajoor.complaint.payment.*}).
     */
    private Duration refundAttemptLease = Duration.ofMinutes(5);

    @NestedConfigurationProperty
    private Attachments attachments = new Attachments();

    @NestedConfigurationProperty
    private Topics topics = new Topics();

    public Duration getRefundAttemptLease() {
        return refundAttemptLease;
    }

    public void setRefundAttemptLease(Duration refundAttemptLease) {
        this.refundAttemptLease = refundAttemptLease;
    }

    public int getMaxDescriptionLength() {
        return maxDescriptionLength;
    }

    public void setMaxDescriptionLength(int maxDescriptionLength) {
        this.maxDescriptionLength = maxDescriptionLength;
    }

    public Duration getAcknowledgmentSla() {
        return acknowledgmentSla;
    }

    public void setAcknowledgmentSla(Duration acknowledgmentSla) {
        this.acknowledgmentSla = acknowledgmentSla;
    }

    public Duration getStatusChangeNotificationSla() {
        return statusChangeNotificationSla;
    }

    public void setStatusChangeNotificationSla(Duration statusChangeNotificationSla) {
        this.statusChangeNotificationSla = statusChangeNotificationSla;
    }

    public Duration getEmergencyResolutionSla() {
        return emergencyResolutionSla;
    }

    public void setEmergencyResolutionSla(Duration emergencyResolutionSla) {
        this.emergencyResolutionSla = emergencyResolutionSla;
    }

    public Duration getStandardResolutionSla() {
        return standardResolutionSla;
    }

    public void setStandardResolutionSla(Duration standardResolutionSla) {
        this.standardResolutionSla = standardResolutionSla;
    }

    public Duration getStatsRefreshWindow() {
        return statsRefreshWindow;
    }

    public void setStatsRefreshWindow(Duration statsRefreshWindow) {
        this.statsRefreshWindow = statsRefreshWindow;
    }

    public Attachments getAttachments() {
        return attachments;
    }

    public void setAttachments(Attachments attachments) {
        this.attachments = attachments;
    }

    public Topics getTopics() {
        return topics;
    }

    public void setTopics(Topics topics) {
        this.topics = topics;
    }

    /** Evidence-attachment limits (Requirement 16.1). */
    public static class Attachments {
        private int maxCount = 5;
        private long maxBytes = 10L * 1024 * 1024;

        public int getMaxCount() {
            return maxCount;
        }

        public void setMaxCount(int maxCount) {
            this.maxCount = maxCount;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    /** Kafka topic names this service produces. */
    public static class Topics {
        private String complaintCreated = "ComplaintCreated";
        private String complaintStatusChanged = "ComplaintStatusChanged";

        public String getComplaintCreated() {
            return complaintCreated;
        }

        public void setComplaintCreated(String complaintCreated) {
            this.complaintCreated = complaintCreated;
        }

        public String getComplaintStatusChanged() {
            return complaintStatusChanged;
        }

        public void setComplaintStatusChanged(String complaintStatusChanged) {
            this.complaintStatusChanged = complaintStatusChanged;
        }
    }
}
