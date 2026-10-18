package com.jihajoor.verification.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable verification-domain settings (Requirement 5).
 *
 * <p>Defaults match the acceptance criteria; all values are overridable via configuration
 * so operations can tune behaviour without a code change. Where documents are stored is
 * {@link DocumentStorageProperties} ({@code jihajoor.verification.document-storage.*}).
 */
@ConfigurationProperties(prefix = "jihajoor.verification")
public class VerificationProperties {

    /**
     * Document types a provider must upload before the status can move to
     * {@code DOCUMENT_SUBMITTED} (Requirement 5.3).
     */
    private List<String> requiredDocumentTypes =
            List.of("GOVERNMENT_ID", "ADDRESS_PROOF", "SKILL_CERTIFICATION");

    public List<String> getRequiredDocumentTypes() {
        return requiredDocumentTypes;
    }

    public void setRequiredDocumentTypes(List<String> requiredDocumentTypes) {
        this.requiredDocumentTypes = requiredDocumentTypes;
    }
}
