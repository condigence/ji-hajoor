package com.jihajoor.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.jihajoor.provider.auth.AuthUserClientPort;
import com.jihajoor.provider.config.ProviderProperties;
import com.jihajoor.provider.domain.AgencyEarningRepository;
import com.jihajoor.provider.domain.EarningType;
import com.jihajoor.provider.domain.InvitationStatus;
import com.jihajoor.provider.domain.ProviderEarning;
import com.jihajoor.provider.domain.ProviderEarningRepository;
import com.jihajoor.provider.domain.ProviderProfile;
import com.jihajoor.provider.domain.ProviderProfileRepository;
import com.jihajoor.provider.domain.SettlementRepository;
import com.jihajoor.provider.domain.Tenant;
import com.jihajoor.provider.domain.Tenant.TenantDetails;
import com.jihajoor.provider.domain.TenantInvitationRepository;
import com.jihajoor.provider.domain.TenantRepository;
import com.jihajoor.provider.service.AgencyInvitationService.InvitationView;
import com.jihajoor.provider.service.AgencyInvitationService.WorkerAgencyView;
import com.jihajoor.provider.support.FakeAuthUserClient;

/**
 * The two supply channels against a real database: an agency invites, the worker alone decides
 * to join, decline or leave; and an agency worker's job credit is split by the agency's share.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = AgencyInvitationJpaTest.JpaConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.datasource.url=jdbc:h2:mem:provider_agency_invitations;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AgencyInvitationJpaTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private ProviderProfileRepository profiles;
    @Autowired
    private TenantInvitationRepository invitationRows;
    @Autowired
    private AgencyEarningRepository agencyEarnings;
    @Autowired
    private ProviderEarningRepository earnings;
    @Autowired
    private SettlementRepository settlements;

    private final UUID agencyAdmin = UUID.randomUUID();
    private FakeAuthUserClient auth;
    private AgencyInvitationService invitations;
    private AgencyEarningsService agencyEarningsService;
    private ProviderService providerService;

    @BeforeEach
    void setUp() {
        agencyEarnings.deleteAll();
        earnings.deleteAll();
        invitationRows.deleteAll();
        profiles.deleteAll();
        tenants.deleteAll();
        auth = new FakeAuthUserClient();
        invitations = new AgencyInvitationService(invitationRows, tenants, profiles, auth);
        agencyEarningsService = new AgencyEarningsService(tenants, agencyEarnings, profiles);
        providerService = new ProviderService(profiles, earnings, settlements, null, null,
                new ProviderProperties(), null, null);
        providerService.setAgencyShareRepositories(tenants, agencyEarnings);
    }

    private Tenant agency(String name) {
        return tenants.saveAndFlush(Tenant.create(new TenantDetails(name, null, null, 25.556, 84.6603,
                new BigDecimal("20"), Set.of(UUID.randomUUID())), UUID.randomUUID()));
    }

    private ProviderProfile worker(String mobile, String name) {
        UUID userId = auth.register(mobile, AuthUserClientPort.SERVICE_PROVIDER);
        ProviderProfile p = ProviderProfile.createWithId(userId);
        p.setDisplayName(name);
        return profiles.saveAndFlush(p);
    }

    private static void assertRefused(Runnable action, HttpStatus status, String errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getErrorCode()).isEqualTo(errorCode);
                });
    }

    @Test
    void anInvitationDoesNotAttachTheWorkerUntilTheyAccept() {
        Tenant ara = agency("Ara Home Services");
        ProviderProfile ravi = worker("+919000000011", "Ravi Kumar");

        InvitationView sent = invitations.invite(ara.getId(), "+919000000011", agencyAdmin);

        assertThat(sent.status()).isEqualTo("PENDING");
        assertThat(profiles.findById(ravi.getId()).orElseThrow().getTenantId()).isNull();
        // Inviting again returns the same open invitation.
        assertThat(invitations.invite(ara.getId(), "+919000000011", agencyAdmin).id()).isEqualTo(sent.id());
        assertThat(invitations.pendingForAgency(ara.getId())).extracting(InvitationView::workerName)
                .containsExactly("Ravi Kumar");

        WorkerAgencyView mine = invitations.forWorker(ravi.getId());
        assertThat(mine.agency()).isNull();
        assertThat(mine.invitations()).extracting(InvitationView::agencyName).containsExactly("Ara Home Services");

        WorkerAgencyView joined = invitations.accept(ravi.getId(), sent.id());

        assertThat(joined.agency().tenantId()).isEqualTo(ara.getId());
        assertThat(joined.invitations()).isEmpty();
        assertThat(profiles.findById(ravi.getId()).orElseThrow().getTenantId()).isEqualTo(ara.getId());
        assertThat(invitationRows.findById(sent.id()).orElseThrow().getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
    }

    @Test
    void acceptingOneInvitationWithdrawsTheOthers_andOnlyTheInvitedWorkerCanAnswer() {
        Tenant alpha = agency("Alpha Agency");
        Tenant beta = agency("Beta Agency");
        ProviderProfile ravi = worker("+919000000011", "Ravi Kumar");
        ProviderProfile mohan = worker("+919000000012", "Mohan");
        InvitationView fromAlpha = invitations.invite(alpha.getId(), "+919000000011", agencyAdmin);
        InvitationView fromBeta = invitations.invite(beta.getId(), "+919000000011", agencyAdmin);

        assertRefused(() -> invitations.accept(mohan.getId(), fromAlpha.id()),
                HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND");

        invitations.accept(ravi.getId(), fromBeta.id());

        assertThat(invitationRows.findById(fromAlpha.id()).orElseThrow().getStatus())
                .isEqualTo(InvitationStatus.CANCELLED);
        assertRefused(() -> invitations.accept(ravi.getId(), fromAlpha.id()),
                HttpStatus.CONFLICT, "INVITATION_NOT_PENDING");
        assertRefused(() -> invitations.invite(alpha.getId(), "+919000000011", agencyAdmin),
                HttpStatus.CONFLICT, "PROVIDER_IN_OTHER_TENANT");
        assertRefused(() -> invitations.invite(beta.getId(), "+919000000011", agencyAdmin),
                HttpStatus.CONFLICT, "PROVIDER_ALREADY_ON_TEAM");
    }

    @Test
    void declineCancelAndLeave() {
        Tenant ara = agency("Ara Home Services");
        ProviderProfile ravi = worker("+919000000011", "Ravi Kumar");
        InvitationView first = invitations.invite(ara.getId(), "+919000000011", agencyAdmin);

        invitations.decline(ravi.getId(), first.id());
        assertThat(invitationRows.findById(first.id()).orElseThrow().getStatus()).isEqualTo(InvitationStatus.DECLINED);
        assertThat(profiles.findById(ravi.getId()).orElseThrow().getTenantId()).isNull();

        InvitationView second = invitations.invite(ara.getId(), "+919000000011", agencyAdmin);
        assertRefused(() -> invitations.cancel(UUID.randomUUID(), second.id(), agencyAdmin),
                HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND");
        invitations.cancel(ara.getId(), second.id(), agencyAdmin);
        assertThat(invitations.forWorker(ravi.getId()).invitations()).isEmpty();

        InvitationView third = invitations.invite(ara.getId(), "+919000000011", agencyAdmin);
        invitations.accept(ravi.getId(), third.id());
        assertThat(invitations.leave(ravi.getId()).agency()).isNull();
        assertThat(profiles.findById(ravi.getId()).orElseThrow().getTenantId()).isNull();
        assertRefused(() -> invitations.leave(ravi.getId()), HttpStatus.CONFLICT, "NOT_IN_AGENCY");
    }

    @Test
    void aCustomerOrAnUnknownNumberCannotBeInvited() {
        Tenant ara = agency("Ara Home Services");
        auth.register("+919000000021", "CUSTOMER");
        for (String mobile : List.of("+919000000021", "+919999999999")) {
            assertRefused(() -> invitations.invite(ara.getId(), mobile, agencyAdmin),
                    HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");
        }
    }

    @Test
    void anAgencyWorkersJobCreditIsSplitByTheAgencyShare_once() {
        Tenant ara = agency("Ara Home Services");
        agencyEarningsService.setShare(ara.getId(), new BigDecimal("10"), agencyAdmin);
        ProviderProfile ravi = worker("+919000000011", "Ravi Kumar");
        invitations.accept(ravi.getId(), invitations.invite(ara.getId(), "+919000000011", agencyAdmin).id());
        UUID booking = UUID.randomUUID();

        // Gross 1000, platform fee 100: the worker's net is 900, the agency takes 10% of it.
        ProviderProfile after = providerService.creditJobEarning(ravi.getId(), booking, "JH-1", new BigDecimal("1000.00"),
                new BigDecimal("100.00"));
        assertThat(after.getWalletBalance()).isEqualByComparingTo("810.00");
        // A re-sent credit changes nothing.
        assertThat(providerService.creditJobEarning(ravi.getId(), booking, "JH-1", new BigDecimal("1000.00"),
                new BigDecimal("100.00")).getWalletBalance()).isEqualByComparingTo("810.00");

        assertThat(earnings.findAll()).extracting(ProviderEarning::getType)
                .containsExactlyInAnyOrder(EarningType.JOB_CREDIT, EarningType.AGENCY_SHARE_DEDUCTION);
        var summary = agencyEarningsService.summary(ara.getId());
        assertThat(summary.sharePercent()).isEqualByComparingTo("10");
        assertThat(summary.totalEarned()).isEqualByComparingTo("90.00");
        assertThat(summary.entries()).singleElement().satisfies(e -> {
            assertThat(e.workerName()).isEqualTo("Ravi Kumar");
            assertThat(e.workerNet()).isEqualByComparingTo("900.00");
            assertThat(e.amount()).isEqualByComparingTo("90.00");
        });
    }

    @Test
    void anIndependentWorkerAndAZeroShareAgencyKeepTheWholeNet() {
        Tenant ara = agency("Ara Home Services");
        ProviderProfile independent = worker("+919000000011", "Ravi Kumar");
        ProviderProfile member = worker("+919000000012", "Mohan");
        invitations.accept(member.getId(), invitations.invite(ara.getId(), "+919000000012", agencyAdmin).id());

        assertThat(providerService.creditJobEarning(independent.getId(), UUID.randomUUID(), "JH-2",
                new BigDecimal("500.00"), new BigDecimal("50.00")).getWalletBalance()).isEqualByComparingTo("450.00");
        assertThat(providerService.creditJobEarning(member.getId(), UUID.randomUUID(), "JH-3",
                new BigDecimal("500.00"), new BigDecimal("50.00")).getWalletBalance()).isEqualByComparingTo("450.00");
        assertThat(agencyEarnings.count()).isZero();
    }

    @Test
    void theShareIsCappedAtHalf() {
        Tenant ara = agency("Ara Home Services");
        for (String bad : List.of("-1", "50.01", "100")) {
            assertRefused(() -> agencyEarningsService.setShare(ara.getId(), new BigDecimal(bad), agencyAdmin),
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
        assertThat(agencyEarningsService.setShare(ara.getId(), new BigDecimal("50"), agencyAdmin)
                .getAgencySharePercent()).isEqualByComparingTo("50");
        assertThat(AgencyEarningsService.shareOf(new BigDecimal("333.33"), new BigDecimal("12.5")))
                .isEqualByComparingTo("41.67");
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = ProviderProfile.class)
    @EnableJpaRepositories(basePackageClasses = ProviderProfileRepository.class)
    static class JpaConfig {
    }
}
