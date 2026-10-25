package com.jihajoor.provider.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The agencies' side of the earnings split. */
public interface AgencyEarningRepository extends JpaRepository<AgencyEarning, UUID> {

    boolean existsByBookingId(UUID bookingId);

    List<AgencyEarning> findTop100ByTenantIdOrderByCreditedAtDesc(UUID tenantId);

    @Query("select sum(e.amount) from AgencyEarning e where e.tenantId = :tenantId")
    BigDecimal totalForTenant(@Param("tenantId") UUID tenantId);
}
