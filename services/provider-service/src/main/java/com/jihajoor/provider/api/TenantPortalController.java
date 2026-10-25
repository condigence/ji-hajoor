package com.jihajoor.provider.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jihajoor.provider.api.dto.MobileNumberRequest;
import com.jihajoor.provider.api.dto.TeamProviderResponse;
import com.jihajoor.provider.api.dto.TenantResponse;
import com.jihajoor.provider.domain.Tenant;
import com.jihajoor.provider.service.AgencyEarningsService;
import com.jihajoor.provider.service.AgencyEarningsService.AgencyEarningsView;
import com.jihajoor.provider.service.AgencyInvitationService;
import com.jihajoor.provider.service.AgencyInvitationService.InvitationView;
import com.jihajoor.provider.service.TenantService;
import com.jihajoor.provider.service.TenantTeamService;

import jakarta.validation.Valid;

/**
 * The Tenant Portal's own-agency endpoints for a Tenant_Admin (Requirements MT-3, MT-8.4, MT-11):
 * the caller's Tenant and its team. The API Gateway routes {@code /tenant/me} and
 * {@code /tenant/providers/**} here ({@code /tenant/bookings/**} goes to booking-service).
 *
 * <p>No path or parameter names a Tenant: every request is scoped to the Tenant the caller
 * administers, resolved from the JWT subject (Requirement MT-10.1, Property MT5), so another
 * Tenant's provider is simply not found (404). A suspended Tenant's administrators get 403
 * {@code TENANT_SUSPENDED} on every action (Requirement MT-1.4). The {@code TENANT_ADMIN} role
 * itself is enforced by {@code ProviderRbacConfig}.
 */
@RestController
@RequestMapping("/tenant")
public class TenantPortalController {

    private final TenantService tenantService;
    private final TenantTeamService teamService;
    private final AgencyInvitationService invitationService;
    private final AgencyEarningsService earningsService;
    private final CallerIdentity callerIdentity;

    public TenantPortalController(TenantService tenantService, TenantTeamService teamService,
                                  AgencyInvitationService invitationService,
                                  AgencyEarningsService earningsService, CallerIdentity callerIdentity) {
        this.tenantService = tenantService;
        this.teamService = teamService;
        this.invitationService = invitationService;
        this.earningsService = earningsService;
        this.callerIdentity = callerIdentity;
    }

    /** {@code GET /tenant/me} — the caller's Tenant. */
    @GetMapping("/me")
    public ResponseEntity<TenantResponse> me() {
        Tenant tenant = tenantService.requirePortalTenant(callerIdentity.requireCallerId());
        return ResponseEntity.ok(TenantResponse.from(tenantService.get(tenant.getId())));
    }

    /** {@code GET /tenant/providers} — the team with availability and assignability. */
    @GetMapping("/providers")
    public ResponseEntity<List<TeamProviderResponse>> providers() {
        Tenant tenant = tenantService.requirePortalTenant(callerIdentity.requireCallerId());
        return ResponseEntity.ok(teamService.team(tenant.getId()).stream()
                .map(TeamProviderResponse::from).toList());
    }

    /**
     * {@code POST /tenant/providers} — invites a worker by mobile number. The worker joins only by
     * accepting in the provider app, so the answer is 202 with the pending invitation.
     */
    @PostMapping("/providers")
    public ResponseEntity<InvitationView> addProvider(@Valid @RequestBody MobileNumberRequest request) {
        UUID caller = callerIdentity.requireCallerId();
        Tenant tenant = tenantService.requirePortalTenant(caller);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(invitationService.invite(tenant.getId(), request.mobileNumber(), caller));
    }

    /** {@code GET /tenant/invitations} — invitations the workers have not answered yet. */
    @GetMapping("/invitations")
    public ResponseEntity<List<InvitationView>> invitations() {
        Tenant tenant = tenantService.requirePortalTenant(callerIdentity.requireCallerId());
        return ResponseEntity.ok(invitationService.pendingForAgency(tenant.getId()));
    }

    /** {@code DELETE /tenant/invitations/{id}} — withdraws one of the agency's own invitations. */
    @DeleteMapping("/invitations/{invitationId}")
    public ResponseEntity<Void> cancelInvitation(@PathVariable("invitationId") UUID invitationId) {
        UUID caller = callerIdentity.requireCallerId();
        Tenant tenant = tenantService.requirePortalTenant(caller);
        invitationService.cancel(tenant.getId(), invitationId, caller);
        return ResponseEntity.noContent().build();
    }

    /** {@code GET /tenant/earnings} — the agency's share setting and what it has earned. */
    @GetMapping("/earnings")
    public ResponseEntity<AgencyEarningsView> earnings() {
        Tenant tenant = tenantService.requirePortalTenant(callerIdentity.requireCallerId());
        return ResponseEntity.ok(earningsService.summary(tenant.getId()));
    }

    /** {@code DELETE /tenant/providers/{providerId}} — removes one of the caller's own providers. */
    @DeleteMapping("/providers/{providerId}")
    public ResponseEntity<Void> removeProvider(@PathVariable("providerId") UUID providerId) {
        UUID caller = callerIdentity.requireCallerId();
        Tenant tenant = tenantService.requirePortalTenant(caller);
        teamService.removeProvider(tenant.getId(), providerId, caller);
        return ResponseEntity.noContent().build();
    }
}
