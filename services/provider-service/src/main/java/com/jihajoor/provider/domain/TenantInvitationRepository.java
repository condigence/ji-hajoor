package com.jihajoor.provider.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Agency invitations to workers. */
public interface TenantInvitationRepository extends JpaRepository<TenantInvitation, UUID> {

    List<TenantInvitation> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, InvitationStatus status);

    List<TenantInvitation> findByProviderIdAndStatusOrderByCreatedAtDesc(UUID providerId, InvitationStatus status);

    Optional<TenantInvitation> findFirstByTenantIdAndProviderIdAndStatus(UUID tenantId, UUID providerId,
                                                                        InvitationStatus status);
}
