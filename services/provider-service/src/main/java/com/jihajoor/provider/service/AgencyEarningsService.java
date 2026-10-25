package com.jihajoor.provider.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jihajoor.provider.domain.AgencyEarning;
import com.jihajoor.provider.domain.AgencyEarningRepository;
import com.jihajoor.provider.domain.ProviderProfile;
import com.jihajoor.provider.domain.ProviderProfileRepository;
import com.jihajoor.provider.domain.Tenant;
import com.jihajoor.provider.domain.TenantRepository;

/**
 * The agency's share of its workers' earnings: the percentage a Platform_Admin sets per agency,
 * and the agency's own earnings ledger. The split itself happens when a job is credited
 * ({@link ProviderService#creditJobEarning}).
 */
@Service
public class AgencyEarningsService {

    private static final Logger log = LoggerFactory.getLogger(AgencyEarningsService.class);

    /** The highest share an agency can take, so a worker always keeps at least half their net. */
    public static final BigDecimal MAX_SHARE_PERCENT = new BigDecimal("50");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final TenantRepository tenants;
    private final AgencyEarningRepository earnings;
    private final ProviderProfileRepository profiles;

    public AgencyEarningsService(TenantRepository tenants, AgencyEarningRepository earnings,
                                 ProviderProfileRepository profiles) {
        this.tenants = tenants;
        this.earnings = earnings;
        this.profiles = profiles;
    }

    /**
     * Sets the agency's share of its workers' net earnings on future jobs.
     *
     * @throws ProviderException 400 {@code VALIDATION_ERROR} outside 0-50; 404 {@code TENANT_NOT_FOUND}
     */
    @Transactional
    public Tenant setShare(UUID tenantId, BigDecimal percent, UUID actor) {
        if (percent == null || percent.signum() < 0 || percent.compareTo(MAX_SHARE_PERCENT) > 0) {
            throw ProviderException.validation("Agency share must be between 0 and 50 percent");
        }
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new ProviderException(
                HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant " + tenantId + " not found"));
        tenant.changeAgencyShare(percent.setScale(2, RoundingMode.HALF_EVEN), actor);
        log.info("Agency {} share set to {}% by {}", tenantId, percent, actor);
        return tenants.saveAndFlush(tenant);
    }

    /** {@code percent}% of {@code workerNet}, to the paisa. */
    static BigDecimal shareOf(BigDecimal workerNet, BigDecimal percent) {
        if (workerNet == null || percent == null || workerNet.signum() <= 0 || percent.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return workerNet.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
    }

    /** The agency's share setting, total earned and its latest 100 entries. */
    @Transactional(readOnly = true)
    public AgencyEarningsView summary(UUID tenantId) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new ProviderException(
                HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant " + tenantId + " not found"));
        List<AgencyEarning> latest = earnings.findTop100ByTenantIdOrderByCreditedAtDesc(tenantId);
        Map<UUID, ProviderProfile> workers = profiles.findAllById(
                        latest.stream().map(AgencyEarning::getProviderId).distinct().toList()).stream()
                .collect(Collectors.toMap(ProviderProfile::getId, Function.identity()));
        List<AgencyEarningEntry> entries = latest.stream()
                .map(e -> new AgencyEarningEntry(e.getBookingId(), e.getBookingReference(), e.getProviderId(),
                        workers.containsKey(e.getProviderId()) ? workers.get(e.getProviderId()).getDisplayName() : null,
                        e.getWorkerNet(), e.getSharePercent(), e.getAmount(), e.getCreditedAt()))
                .toList();
        BigDecimal total = earnings.totalForTenant(tenantId);
        return new AgencyEarningsView(tenant.getAgencySharePercent(), total == null ? BigDecimal.ZERO : total, entries);
    }

    /** One job's split as the agency sees it. */
    public record AgencyEarningEntry(UUID bookingId, String bookingReference, UUID providerId, String workerName,
                                     BigDecimal workerNet, BigDecimal sharePercent, BigDecimal amount,
                                     Instant creditedAt) {
    }

    /** The agency's earnings page. */
    public record AgencyEarningsView(BigDecimal sharePercent, BigDecimal totalEarned,
                                     List<AgencyEarningEntry> entries) {
    }
}
