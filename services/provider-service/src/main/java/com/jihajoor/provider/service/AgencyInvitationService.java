package com.jihajoor.provider.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jihajoor.provider.auth.AuthUserClientPort;
import com.jihajoor.provider.auth.AuthUserClientPort.AuthUser;
import com.jihajoor.provider.domain.InvitationStatus;
import com.jihajoor.provider.domain.ProviderProfile;
import com.jihajoor.provider.domain.ProviderProfileRepository;
import com.jihajoor.provider.domain.Tenant;
import com.jihajoor.provider.domain.TenantInvitation;
import com.jihajoor.provider.domain.TenantInvitationRepository;
import com.jihajoor.provider.domain.TenantRepository;

/**
 * The two supply channels meet here. Independent workers register on their own; an agency grows
 * its team by inviting a worker, who joins only by accepting in the provider app. An agency
 * therefore cannot take over somebody's account just by knowing their mobile number. A
 * Platform_Admin can still attach a worker directly ({@link TenantTeamService#addProvider}).
 *
 * <p>A worker can also leave their agency and work independently again.
 */
@Service
public class AgencyInvitationService {

    private static final Logger log = LoggerFactory.getLogger(AgencyInvitationService.class);

    /** An agency may have at most this many unanswered invitations at once. */
    static final int MAX_PENDING_PER_AGENCY = 200;

    private final TenantInvitationRepository invitations;
    private final TenantRepository tenants;
    private final ProviderProfileRepository profiles;
    private final AuthUserClientPort auth;

    public AgencyInvitationService(TenantInvitationRepository invitations, TenantRepository tenants,
                                   ProviderProfileRepository profiles, AuthUserClientPort auth) {
        this.invitations = invitations;
        this.tenants = tenants;
        this.profiles = profiles;
        this.auth = auth;
    }

    /**
     * Invites the worker registered with {@code mobileNumber} to the agency. Inviting again while
     * an invitation is open returns that invitation.
     *
     * @throws ProviderException 404 {@code TENANT_NOT_FOUND} / {@code PROVIDER_NOT_FOUND};
     *         409 {@code PROVIDER_ALREADY_ON_TEAM}, {@code PROVIDER_IN_OTHER_TENANT},
     *         {@code TOO_MANY_PENDING_INVITATIONS}; 503 {@code AUTH_UNAVAILABLE}
     */
    @Transactional
    public InvitationView invite(UUID tenantId, String mobileNumber, UUID actor) {
        Tenant tenant = requireTenant(tenantId);
        String number = TenantService.normaliseMobile(mobileNumber);
        ProviderProfile worker = auth.findByMobile(number)
                .filter(user -> user.hasRole(AuthUserClientPort.SERVICE_PROVIDER))
                .map(AuthUser::userId)
                .flatMap(profiles::findByUserId)
                .orElseThrow(() -> ProviderException.notFound(
                        "No worker with a profile is registered with that mobile number"));
        if (tenantId.equals(worker.getTenantId())) {
            throw new ProviderException(HttpStatus.CONFLICT, "PROVIDER_ALREADY_ON_TEAM",
                    "That worker is already on your team");
        }
        if (worker.getTenantId() != null) {
            throw new ProviderException(HttpStatus.CONFLICT, "PROVIDER_IN_OTHER_TENANT",
                    "That worker already belongs to another agency");
        }
        Optional<TenantInvitation> open = invitations.findFirstByTenantIdAndProviderIdAndStatus(
                tenantId, worker.getId(), InvitationStatus.PENDING);
        if (open.isPresent()) {
            return view(open.get(), tenant, worker, number);
        }
        if (invitations.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, InvitationStatus.PENDING)
                .size() >= MAX_PENDING_PER_AGENCY) {
            throw new ProviderException(HttpStatus.CONFLICT, "TOO_MANY_PENDING_INVITATIONS",
                    "Cancel some unanswered invitations before sending more");
        }
        TenantInvitation saved = invitations.saveAndFlush(
                TenantInvitation.pending(tenantId, worker.getId(), actor));
        log.info("Agency {} invited worker {} (by {})", tenantId, worker.getId(), actor);
        return view(saved, tenant, worker, number);
    }

    /** The agency's unanswered invitations, newest first. */
    @Transactional(readOnly = true)
    public List<InvitationView> pendingForAgency(UUID tenantId) {
        Tenant tenant = requireTenant(tenantId);
        List<TenantInvitation> open =
                invitations.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, InvitationStatus.PENDING);
        Map<UUID, ProviderProfile> workers = profiles.findAllById(
                        open.stream().map(TenantInvitation::getProviderId).toList()).stream()
                .collect(Collectors.toMap(ProviderProfile::getId, Function.identity()));
        return open.stream()
                .map(i -> view(i, tenant, workers.get(i.getProviderId()), null))
                .toList();
    }

    /**
     * Withdraws one of the agency's own unanswered invitations.
     *
     * @throws ProviderException 404 {@code INVITATION_NOT_FOUND} for another agency's invitation
     */
    @Transactional
    public void cancel(UUID tenantId, UUID invitationId, UUID actor) {
        TenantInvitation invitation = invitations.findById(invitationId)
                .filter(i -> i.getTenantId().equals(tenantId))
                .orElseThrow(AgencyInvitationService::invitationNotFound);
        invitation.close(InvitationStatus.CANCELLED);
        invitations.save(invitation);
        log.info("Agency {} invitation {} cancelled by {}", tenantId, invitationId, actor);
    }

    /** The worker's agency (if any) and the invitations waiting for an answer. */
    @Transactional(readOnly = true)
    public WorkerAgencyView forWorker(UUID providerId) {
        Optional<AgencyRef> current = profiles.findById(providerId)
                .map(ProviderProfile::getTenantId)
                .flatMap(tenants::findById)
                .map(AgencyRef::of);
        List<TenantInvitation> open =
                invitations.findByProviderIdAndStatusOrderByCreatedAtDesc(providerId, InvitationStatus.PENDING);
        Map<UUID, Tenant> agencies = tenants.findAllById(
                        open.stream().map(TenantInvitation::getTenantId).distinct().toList()).stream()
                .collect(Collectors.toMap(Tenant::getId, Function.identity()));
        List<InvitationView> pending = open.stream()
                .filter(i -> agencies.containsKey(i.getTenantId()) && agencies.get(i.getTenantId()).isActive())
                .map(i -> view(i, agencies.get(i.getTenantId()), null, null))
                .toList();
        return new WorkerAgencyView(current.orElse(null), pending);
    }

    /**
     * The worker joins the inviting agency. Their other open invitations are withdrawn.
     *
     * @throws ProviderException 404 {@code INVITATION_NOT_FOUND}; 409 {@code INVITATION_NOT_PENDING},
     *         {@code TENANT_NOT_ACTIVE}, {@code PROVIDER_IN_OTHER_TENANT}
     */
    @Transactional
    public WorkerAgencyView accept(UUID providerId, UUID invitationId) {
        TenantInvitation invitation = ownPending(providerId, invitationId);
        Tenant tenant = tenants.findById(invitation.getTenantId())
                .orElseThrow(AgencyInvitationService::invitationNotFound);
        if (!tenant.isActive()) {
            throw new ProviderException(HttpStatus.CONFLICT, "TENANT_NOT_ACTIVE",
                    "That agency is not active on Ji-Hajoor right now");
        }
        if (profiles.attachToTenant(providerId, tenant.getId()) != 1) {
            UUID current = profiles.findById(providerId).map(ProviderProfile::getTenantId).orElse(null);
            if (!tenant.getId().equals(current)) {
                throw new ProviderException(HttpStatus.CONFLICT, "PROVIDER_IN_OTHER_TENANT",
                        "Leave your current agency before joining another");
            }
        }
        // attachToTenant clears the persistence context, so every change is saved explicitly.
        invitation.close(InvitationStatus.ACCEPTED);
        invitations.save(invitation);
        invitations.findByProviderIdAndStatusOrderByCreatedAtDesc(providerId, InvitationStatus.PENDING)
                .stream()
                .filter(other -> !other.getId().equals(invitationId))
                .forEach(other -> {
                    other.close(InvitationStatus.CANCELLED);
                    invitations.save(other);
                });
        invitations.flush();
        log.info("Worker {} joined agency {}", providerId, tenant.getId());
        return forWorker(providerId);
    }

    /** The worker turns an invitation down. */
    @Transactional
    public WorkerAgencyView decline(UUID providerId, UUID invitationId) {
        TenantInvitation invitation = ownPending(providerId, invitationId);
        invitation.close(InvitationStatus.DECLINED);
        invitations.saveAndFlush(invitation);
        log.info("Worker {} declined invitation {}", providerId, invitationId);
        return forWorker(providerId);
    }

    /**
     * The worker leaves their agency and works independently again. Bookings already assigned
     * to them stay as they are.
     *
     * @throws ProviderException 409 {@code NOT_IN_AGENCY}
     */
    @Transactional
    public WorkerAgencyView leave(UUID providerId) {
        UUID current = profiles.findById(providerId).map(ProviderProfile::getTenantId)
                .orElseThrow(() -> new ProviderException(HttpStatus.CONFLICT, "NOT_IN_AGENCY",
                        "You are not part of an agency"));
        if (profiles.detachFromTenant(providerId, current) == 0) {
            throw new ProviderException(HttpStatus.CONFLICT, "NOT_IN_AGENCY", "You are not part of an agency");
        }
        log.info("Worker {} left agency {}", providerId, current);
        return forWorker(providerId);
    }

    // ----------------------------------------------------------------------------------------

    private TenantInvitation ownPending(UUID providerId, UUID invitationId) {
        TenantInvitation invitation = invitations.findById(invitationId)
                .filter(i -> i.getProviderId().equals(providerId))
                .orElseThrow(AgencyInvitationService::invitationNotFound);
        if (!invitation.isPending()) {
            throw new ProviderException(HttpStatus.CONFLICT, "INVITATION_NOT_PENDING",
                    "That invitation has already been answered or withdrawn");
        }
        return invitation;
    }

    private Tenant requireTenant(UUID tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> new ProviderException(HttpStatus.NOT_FOUND,
                "TENANT_NOT_FOUND", "Tenant " + tenantId + " not found"));
    }

    private static ProviderException invitationNotFound() {
        return new ProviderException(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "Invitation not found");
    }

    private static InvitationView view(TenantInvitation i, Tenant tenant, ProviderProfile worker, String mobile) {
        return new InvitationView(i.getId(), i.getTenantId(), tenant == null ? null : tenant.getName(),
                i.getProviderId(), worker == null ? null : worker.getDisplayName(), mobile,
                i.getStatus().name(), i.getCreatedAt(),
                tenant == null ? null : tenant.getAgencySharePercent());
    }

    /**
     * One invitation. {@code workerName} and {@code mobileNumber} are filled only for the agency's
     * side; {@code agencySharePercent} tells the worker what share of their net the agency takes.
     */
    public record InvitationView(UUID id, UUID tenantId, String agencyName, UUID providerId,
                                 String workerName, String mobileNumber, String status, Instant createdAt,
                                 java.math.BigDecimal agencySharePercent) {
    }

    /** The agency a worker belongs to. */
    public record AgencyRef(UUID tenantId, String name, java.math.BigDecimal agencySharePercent) {
        static AgencyRef of(Tenant t) {
            return new AgencyRef(t.getId(), t.getName(), t.getAgencySharePercent());
        }
    }

    /** A worker's agency (null when independent) and the invitations awaiting their answer. */
    public record WorkerAgencyView(AgencyRef agency, List<InvitationView> invitations) {
    }
}
