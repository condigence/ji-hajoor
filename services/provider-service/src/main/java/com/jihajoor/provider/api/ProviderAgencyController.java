package com.jihajoor.provider.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jihajoor.provider.service.AgencyInvitationService;
import com.jihajoor.provider.service.AgencyInvitationService.WorkerAgencyView;

/**
 * A worker's own agency membership in the provider app: the agency they belong to, invitations
 * waiting for an answer, accepting or declining one, and leaving to work independently.
 *
 * <p>Always the caller's own record: the path says {@code me} and the worker is the JWT subject,
 * so no request can answer another worker's invitation. {@code SERVICE_PROVIDER} only
 * ({@code ProviderRbacConfig}).
 */
@RestController
@RequestMapping("/providers/me/agency")
public class ProviderAgencyController {

    private final AgencyInvitationService invitations;
    private final CallerIdentity callerIdentity;

    public ProviderAgencyController(AgencyInvitationService invitations, CallerIdentity callerIdentity) {
        this.invitations = invitations;
        this.callerIdentity = callerIdentity;
    }

    /** {@code GET /providers/me/agency} — current agency (null when independent) and open invitations. */
    @GetMapping
    public ResponseEntity<WorkerAgencyView> mine() {
        return ResponseEntity.ok(invitations.forWorker(callerIdentity.requireCallerId()));
    }

    /** {@code POST /providers/me/agency/invitations/{id}/accept} — join that agency. */
    @PostMapping("/invitations/{invitationId}/accept")
    public ResponseEntity<WorkerAgencyView> accept(@PathVariable("invitationId") UUID invitationId) {
        return ResponseEntity.ok(invitations.accept(callerIdentity.requireCallerId(), invitationId));
    }

    /** {@code POST /providers/me/agency/invitations/{id}/decline} — turn it down. */
    @PostMapping("/invitations/{invitationId}/decline")
    public ResponseEntity<WorkerAgencyView> decline(@PathVariable("invitationId") UUID invitationId) {
        return ResponseEntity.ok(invitations.decline(callerIdentity.requireCallerId(), invitationId));
    }

    /** {@code POST /providers/me/agency/leave} — leave the agency and work independently. */
    @PostMapping("/leave")
    public ResponseEntity<WorkerAgencyView> leave() {
        return ResponseEntity.ok(invitations.leave(callerIdentity.requireCallerId()));
    }
}
