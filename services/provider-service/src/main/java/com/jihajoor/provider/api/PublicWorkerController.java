package com.jihajoor.provider.api;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.jihajoor.provider.domain.ProviderProfile;
import com.jihajoor.provider.domain.ProviderProfileRepository;
import com.jihajoor.provider.domain.Tenant;
import com.jihajoor.provider.domain.TenantRepository;
import com.jihajoor.provider.service.ProviderException;
import com.jihajoor.provider.verification.VerificationAdminClientPort;

/**
 * {@code GET /public/workers/{workerId}} — a worker's public page, the link they share to win
 * customers: name, skills, experience, rating, agency and how long they have been with Ji-Hajoor.
 *
 * <p>Only a worker Ji-Hajoor has verified, and who is not under review, has a page; anyone else
 * (unknown, unverified, suspended) is simply not found, so the endpoint cannot be used to discover
 * accounts. No contact details, location or documents are ever included.
 */
@RestController
public class PublicWorkerController {

    static final String APPROVED = "APPROVED";

    private final ProviderProfileRepository profiles;
    private final TenantRepository tenants;
    private final VerificationAdminClientPort verification;

    public PublicWorkerController(ProviderProfileRepository profiles, TenantRepository tenants,
                                  VerificationAdminClientPort verification) {
        this.profiles = profiles;
        this.tenants = tenants;
        this.verification = verification;
    }

    @GetMapping("/public/workers/{workerId}")
    public ResponseEntity<PublicWorker> worker(@PathVariable("workerId") UUID workerId) {
        ProviderProfile profile = profiles.findById(workerId)
                .filter(p -> !p.isUnderReview())
                .filter(p -> verification.statusesOf(List.of(workerId))
                        .map(statuses -> APPROVED.equals(statuses.get(workerId)))
                        .orElse(false))
                .orElseThrow(() -> ProviderException.notFound("Worker not found"));
        String agency = profile.getTenantId() == null ? null
                : tenants.findById(profile.getTenantId()).filter(Tenant::isActive).map(Tenant::getName).orElse(null);
        String since = profile.getCreatedAt() == null ? null
                : YearMonth.from(profile.getCreatedAt().atZone(ZoneOffset.UTC)).toString();
        PublicWorker body = new PublicWorker(profile.getId(), profile.getDisplayName(),
                List.copyOf(profile.getSkillTags()), profile.getYearsExperience(),
                profile.getRatingCount() > 0 ? profile.getAggregateRating() : null, profile.getRatingCount(),
                agency, since, true);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
                .body(body);
    }

    /** What anyone may see about a verified worker. {@code memberSince} is "YYYY-MM". */
    public record PublicWorker(UUID id, String displayName, List<String> skills, int yearsExperience,
                               BigDecimal rating, int ratingCount, String agencyName, String memberSince,
                               boolean verified) {
    }
}
