package com.jihajoor.provider.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jihajoor.provider.alert.AdminAlertPort;
import com.jihajoor.provider.bank.BankAccountCodec;
import com.jihajoor.provider.bank.BankAccountDetails;
import com.jihajoor.provider.bank.BankAccountVerificationPort;
import com.jihajoor.provider.bank.BankAccountView;
import com.jihajoor.provider.catalog.CatalogClientPort;
import com.jihajoor.provider.config.ProviderProperties;
import com.jihajoor.provider.domain.AvailabilitySlot;
import com.jihajoor.provider.domain.ProviderCategorySelection;
import com.jihajoor.provider.domain.AgencyEarning;
import com.jihajoor.provider.domain.AgencyEarningRepository;
import com.jihajoor.provider.domain.EarningType;
import com.jihajoor.provider.domain.ProviderEarning;
import com.jihajoor.provider.domain.ProviderEarningRepository;
import com.jihajoor.provider.domain.ProviderProfile;
import com.jihajoor.provider.domain.ProviderProfileRepository;
import com.jihajoor.provider.domain.RatingUpdateOutcome;
import com.jihajoor.provider.domain.Settlement;
import com.jihajoor.provider.domain.SettlementRepository;
import com.jihajoor.provider.domain.Tenant;
import com.jihajoor.provider.domain.TenantRepository;
import com.jihajoor.provider.service.ProfileUpdateCommand.CategorySelectionCommand;

/**
 * Core provider-domain business logic: profile/skills/radius, availability schedule,
 * emergency-availability toggle, wallet balance, earnings history, settlement requests, and
 * rating-based Admin-review flagging (Requirements 4 and 14).
 *
 * <p>External dependencies are expressed as ports ({@link CatalogClientPort},
 * {@link AdminAlertPort}, {@link BankAccountVerificationPort}) so the logic is fully unit-testable.
 * Encryption at rest goes through {@link BankAccountCodec}; a settlement copies the account's
 * ciphertext and never encrypts a destination of its own.
 */
@Service
public class ProviderService {

    private static final Logger log = LoggerFactory.getLogger(ProviderService.class);

    private final ProviderProfileRepository profileRepository;
    private final ProviderEarningRepository earningRepository;
    private final SettlementRepository settlementRepository;
    private final CatalogClientPort catalogClient;
    private final AdminAlertPort adminAlert;
    private final ProviderProperties props;
    private final BankAccountCodec bankAccountCodec;
    private final BankAccountVerificationPort bankAccountVerification;

    public ProviderService(ProviderProfileRepository profileRepository,
                           ProviderEarningRepository earningRepository,
                           SettlementRepository settlementRepository,
                           CatalogClientPort catalogClient,
                           AdminAlertPort adminAlert,
                           ProviderProperties props,
                           BankAccountCodec bankAccountCodec,
                           BankAccountVerificationPort bankAccountVerification) {
        this.profileRepository = profileRepository;
        this.earningRepository = earningRepository;
        this.settlementRepository = settlementRepository;
        this.catalogClient = catalogClient;
        this.adminAlert = adminAlert;
        this.props = props;
        this.bankAccountCodec = bankAccountCodec;
        this.bankAccountVerification = bankAccountVerification;
    }

    /** Set when the agency-share tables are available; without them every job pays the worker in full. */
    private TenantRepository tenantRepository;
    private AgencyEarningRepository agencyEarningRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAgencyShareRepositories(TenantRepository tenantRepository,
                                    AgencyEarningRepository agencyEarningRepository) {
        this.tenantRepository = tenantRepository;
        this.agencyEarningRepository = agencyEarningRepository;
    }

    // ================= Profile (Requirement 4.1–4.3, 4.8) =================

    @Transactional
    public ProviderProfile updateProfile(UUID providerId, ProfileUpdateCommand cmd) {
        validateSkillTags(cmd.skillTags());
        validateYearsExperience(cmd.yearsExperience());
        validateServiceRadius(cmd.serviceRadiusKm());
        validateBaseLocation(cmd.baseLatitude(), cmd.baseLongitude());
        List<ProviderCategorySelection> selections = validateAndBuildSelections(cmd.categories());

        ProviderProfile profile = getOrCreate(providerId);
        if (cmd.baseLatitude() != null) {
            // Both-or-neither is validated above; neither means "keep the location on file".
            profile.setBaseLocation(cmd.baseLatitude(), cmd.baseLongitude());
        }
        profile.setDisplayName(cmd.displayName());
        profile.replaceSkillTags(cmd.skillTags());
        profile.setYearsExperience(cmd.yearsExperience());
        profile.setServiceRadiusKm(cmd.serviceRadiusKm());
        profile.replaceCategorySelections(selections);
        return profileRepository.save(profile);
    }

    private void validateSkillTags(List<String> tags) {
        int count = tags == null ? 0 : tags.size();
        if (count < props.getMinSkillTags() || count > props.getMaxSkillTags()) {
            throw ProviderException.validation(
                    "Skill tags must number between " + props.getMinSkillTags()
                            + " and " + props.getMaxSkillTags() + " (got " + count + ")");
        }
    }

    private void validateYearsExperience(int years) {
        if (years < props.getMinYearsExperience() || years > props.getMaxYearsExperience()) {
            throw ProviderException.validation(
                    "Years of experience must be between " + props.getMinYearsExperience()
                            + " and " + props.getMaxYearsExperience() + " (got " + years + ")");
        }
    }

    /** Requirement 4.2: radius outside 1–100 km is rejected with the permitted range. */
    private void validateServiceRadius(int radiusKm) {
        if (radiusKm < props.getMinServiceRadiusKm() || radiusKm > props.getMaxServiceRadiusKm()) {
            throw ProviderException.validation(
                    "Service radius must be between " + props.getMinServiceRadiusKm()
                            + " and " + props.getMaxServiceRadiusKm() + " kilometers (got " + radiusKm + ")");
        }
    }

    /**
     * Base service location (Requirement 8.2): both coordinates or neither, each finite and within
     * the WGS84 range. The DTO's bean-validation bounds cover the HTTP path; this is the
     * authoritative check for every caller of the service.
     */
    private void validateBaseLocation(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) {
            return;
        }
        if (latitude == null || longitude == null) {
            throw ProviderException.validation(
                    "baseLatitude and baseLongitude must be provided together");
        }
        if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw ProviderException.validation(
                    "baseLatitude must be between -90 and 90 (got " + latitude + ")");
        }
        if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw ProviderException.validation(
                    "baseLongitude must be between -180 and 180 (got " + longitude + ")");
        }
    }

    private List<ProviderCategorySelection> validateAndBuildSelections(List<CategorySelectionCommand> categories) {
        List<CategorySelectionCommand> cats = categories == null ? List.of() : categories;
        if (cats.size() > props.getMaxActiveCategories()) {
            throw ProviderException.validation(
                    "A provider may select at most " + props.getMaxActiveCategories()
                            + " active service categories (got " + cats.size() + ")");
        }

        List<ProviderCategorySelection> result = new ArrayList<>();
        for (CategorySelectionCommand cat : cats) {
            List<UUID> subs = cat.subcategoryIds() == null ? List.of() : cat.subcategoryIds();
            if (subs.size() > props.getMaxSubcategoriesPerCategory()) {
                throw ProviderException.validation(
                        "Category " + cat.categoryId() + " has " + subs.size()
                                + " subcategories; the maximum per category is "
                                + props.getMaxSubcategoriesPerCategory());
            }
            // Requirement 4.3, 4.8: reject deactivated categories/subcategories via the catalog.
            if (!catalogClient.isCategoryActive(cat.categoryId())) {
                throw new ProviderException(HttpStatus.BAD_REQUEST, "CATEGORY_DEACTIVATED",
                        "Service category " + cat.categoryId() + " is deactivated or does not exist");
            }
            for (UUID sub : subs) {
                if (!catalogClient.isSubcategoryActive(cat.categoryId(), sub)) {
                    throw new ProviderException(HttpStatus.BAD_REQUEST, "SUBCATEGORY_DEACTIVATED",
                            "Service subcategory " + sub + " is deactivated or does not exist under category "
                                    + cat.categoryId());
                }
            }
            result.add(new ProviderCategorySelection(cat.categoryId(), subs));
        }
        return result;
    }

    // ================= Radius (Requirement 4.4) =================

    @Transactional
    public ProviderProfile updateServiceRadius(UUID providerId, int radiusKm) {
        validateServiceRadius(radiusKm);
        ProviderProfile profile = getExisting(providerId);
        profile.setServiceRadiusKm(radiusKm);
        return profileRepository.save(profile);
    }

    // ================= Emergency availability (Requirement 4.6) =================

    @Transactional
    public ProviderProfile updateEmergencyAvailability(UUID providerId, boolean available) {
        ProviderProfile profile = getExisting(providerId);
        profile.setEmergencyAvailable(available);
        return profileRepository.save(profile);
    }

    // ================= Availability schedule (Requirement 4.5) =================

    @Transactional
    public ProviderProfile updateAvailability(UUID providerId, AvailabilityCommand cmd) {
        List<AvailabilitySlot> slots = buildAndValidateSlots(cmd);
        ProviderProfile profile = getExisting(providerId);
        profile.replaceAvailability(slots);
        return profileRepository.save(profile);
    }

    private List<AvailabilitySlot> buildAndValidateSlots(AvailabilityCommand cmd) {
        List<AvailabilityCommand.Slot> input = cmd.slots() == null ? List.of() : cmd.slots();
        List<AvailabilitySlot> slots = new ArrayList<>();
        for (AvailabilityCommand.Slot s : input) {
            if (s.dayOfWeek() == null) {
                throw ProviderException.validation("Availability slot day-of-week is required");
            }
            if (s.startHour() < 0 || s.startHour() > 23) {
                throw ProviderException.validation(
                        "Availability slot startHour must be in [0,23] (got " + s.startHour() + ")");
            }
            if (s.endHour() < 1 || s.endHour() > 24) {
                throw ProviderException.validation(
                        "Availability slot endHour must be in [1,24] (got " + s.endHour() + ")");
            }
            if (s.endHour() <= s.startHour()) {
                throw ProviderException.validation(
                        "Availability slot endHour (" + s.endHour() + ") must be after startHour ("
                                + s.startHour() + ")");
            }
            slots.add(new AvailabilitySlot(s.dayOfWeek(), s.startHour(), s.endHour()));
        }
        // Requirement 4.5: reject overlapping slots.
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                if (slots.get(i).overlaps(slots.get(j))) {
                    AvailabilitySlot a = slots.get(i);
                    AvailabilitySlot b = slots.get(j);
                    throw new ProviderException(HttpStatus.BAD_REQUEST, "OVERLAPPING_AVAILABILITY",
                            "Availability slots overlap on " + a.getDayOfWeek() + ": ["
                                    + a.getStartHour() + "," + a.getEndHour() + ") and ["
                                    + b.getStartHour() + "," + b.getEndHour() + ")");
                }
            }
        }
        return slots;
    }

    // ================= Wallet & earnings (Requirement 14.1, 14.5) =================

    /**
     * Credits a completed job's net earning to the wallet and records an itemised earnings
     * line showing gross, platform fee, and net (Requirement 14.1).
     *
     * <p>Idempotent per booking: the Payment Service delivers the credit at least once, so a
     * second credit for a booking that already has its JOB_CREDIT line changes nothing. The check
     * is scoped by provider as well: a booking pays exactly one provider, so a credit of a booking
     * already credited to a <em>different</em> provider is not a repeat but a contradiction, and is
     * refused with 409 {@code EARNING_BOOKING_CONFLICT} instead of being silently "applied".
     *
     * <p>Two deliveries that race both pass the check; the unique index (migration V2) then refuses
     * the second insert. The line is flushed here so that refusal surfaces as a
     * {@link org.springframework.dao.DataIntegrityViolationException} from this call, which rolls the
     * transaction back; the caller then answers through {@link #jobCreditAlreadyApplied} instead of
     * a 500.
     *
     * @throws ProviderException 409 {@code EARNING_BOOKING_CONFLICT} when the booking was credited to
     *                           another provider
     */
    @Transactional
    public ProviderProfile creditJobEarning(UUID providerId, UUID bookingId, String bookingReference,
                                             BigDecimal gross, BigDecimal platformFee) {
        if (gross == null || gross.signum() < 0) {
            throw ProviderException.validation("Gross earning must be non-negative");
        }
        if (platformFee == null || platformFee.signum() < 0) {
            throw ProviderException.validation("Platform fee must be non-negative");
        }
        if (platformFee.compareTo(gross) > 0) {
            throw ProviderException.validation("Platform fee cannot exceed gross earning");
        }
        ProviderProfile profile = getExisting(providerId);
        if (bookingId != null) {
            Optional<ProviderEarning> applied =
                    earningRepository.findFirstByBookingIdAndType(bookingId, EarningType.JOB_CREDIT);
            if (applied.isPresent()) {
                requireSameProvider(applied.get(), providerId);
                log.info("Job credit for booking {} already applied to provider {}; ignoring the repeat",
                        bookingId, providerId);
                return profile;
            }
        }
        ProviderEarning earning = ProviderEarning.jobCredit(providerId, bookingId, bookingReference, gross, platformFee);
        earningRepository.saveAndFlush(earning);
        BigDecimal workerCredit = earning.getNet();
        BigDecimal agencyShare = creditAgencyShare(profile, bookingId, bookingReference, earning.getNet());
        if (agencyShare.signum() > 0) {
            earningRepository.saveAndFlush(
                    ProviderEarning.agencyShare(providerId, bookingId, bookingReference, agencyShare));
            workerCredit = workerCredit.subtract(agencyShare);
        }
        profile.creditWallet(workerCredit);
        return profileRepository.save(profile);
    }

    /**
     * Records the agency's share when the worker belongs to an agency that takes one, and returns
     * it (zero otherwise). The job credit's unique index already makes a booking pay once; the
     * agency ledger has its own per-booking index as well.
     */
    private BigDecimal creditAgencyShare(ProviderProfile worker, UUID bookingId, String bookingReference,
                                         BigDecimal workerNet) {
        if (bookingId == null || worker.getTenantId() == null
                || tenantRepository == null || agencyEarningRepository == null) {
            return BigDecimal.ZERO;
        }
        Tenant agency = tenantRepository.findById(worker.getTenantId()).orElse(null);
        if (agency == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal share = AgencyEarningsService.shareOf(workerNet, agency.getAgencySharePercent());
        if (share.signum() <= 0 || agencyEarningRepository.existsByBookingId(bookingId)) {
            return BigDecimal.ZERO;
        }
        agencyEarningRepository.saveAndFlush(new AgencyEarning(agency.getId(), worker.getId(), bookingId,
                bookingReference, workerNet, agency.getAgencySharePercent(), share));
        log.info("Booking {}: agency {} share {} of worker {} net {}", bookingId, agency.getId(), share,
                worker.getId(), workerNet);
        return share;
    }

    /**
     * The answer to a job credit that lost a race with another delivery of the same booking's credit
     * (the unique index refused its insert, see {@link #creditJobEarning}): the provider's profile
     * with the balance the winning delivery produced, exactly as for a repeat that came later.
     *
     * @return empty when the booking has no job credit after all, i.e. the refused insert was not a
     *         duplicate and the caller should surface the original failure
     * @throws ProviderException 409 {@code EARNING_BOOKING_CONFLICT} when the winning credit went to
     *                           another provider; 404 for an unknown provider
     */
    @Transactional(readOnly = true)
    public Optional<ProviderProfile> jobCreditAlreadyApplied(UUID providerId, UUID bookingId) {
        if (bookingId == null) {
            return Optional.empty();
        }
        Optional<ProviderEarning> applied =
                earningRepository.findFirstByBookingIdAndType(bookingId, EarningType.JOB_CREDIT);
        if (applied.isEmpty()) {
            return Optional.empty();
        }
        requireSameProvider(applied.get(), providerId);
        log.info("Job credit for booking {} was applied by a concurrent delivery; answering as a repeat",
                bookingId);
        return Optional.of(getExisting(providerId));
    }

    private static void requireSameProvider(ProviderEarning applied, UUID providerId) {
        if (!applied.getProviderId().equals(providerId)) {
            log.error("Job credit for booking {} sent for provider {} but already applied to provider {}",
                    applied.getBookingId(), providerId, applied.getProviderId());
            throw new ProviderException(HttpStatus.CONFLICT, "EARNING_BOOKING_CONFLICT",
                    "Booking " + applied.getBookingId() + " was already credited to another provider");
        }
    }

    /** Records a complaint-related penalty deduction, itemised separately (Requirement 14.1). */
    @Transactional
    public ProviderProfile applyPenalty(UUID providerId, UUID bookingId, String bookingReference, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw ProviderException.validation("Penalty amount must be positive");
        }
        ProviderProfile profile = getExisting(providerId);
        earningRepository.save(ProviderEarning.penalty(providerId, bookingId, bookingReference, amount));
        profile.debitWallet(amount);
        return profileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public Page<ProviderEarning> earningsHistory(UUID providerId, Pageable pageable) {
        // No profile yet means no earnings yet: the ledger query simply finds nothing.
        return earningRepository.findByProviderIdOrderByCreditedAtDesc(providerId, pageable);
    }

    // ================= Settlement (Requirement 14.2, 4.9) =================

    @Transactional
    public Settlement requestSettlement(UUID providerId, SettlementCommand cmd) {
        ProviderProfile profile = getExisting(providerId);

        BigDecimal amount = cmd.amount();
        if (amount == null || amount.compareTo(props.getMinSettlementAmount()) < 0) {
            throw new ProviderException(HttpStatus.BAD_REQUEST, "SETTLEMENT_AMOUNT_TOO_LOW",
                    "Settlement amount must be at least " + props.getMinSettlementAmount());
        }
        if (amount.compareTo(profile.getWalletBalance()) > 0) {
            throw new ProviderException(HttpStatus.BAD_REQUEST, "SETTLEMENT_AMOUNT_EXCEEDS_BALANCE",
                    "Settlement amount " + amount + " exceeds available wallet balance "
                            + profile.getWalletBalance());
        }
        if (!profile.isBankAccountVerified()) {
            throw new ProviderException(HttpStatus.BAD_REQUEST, "NO_VERIFIED_BANK_ACCOUNT",
                    "A verified bank account is required before requesting a settlement");
        }

        // Requirement 14.3: the transfer goes to the provider's verified registered account, and only
        // there. Any other destination is refused, so a payout can never be directed to an account
        // nobody verified (previously any other reference was encrypted and used as the destination).
        if (!usesStoredAccount(profile, cmd.bankAccountRef())) {
            throw new ProviderException(HttpStatus.BAD_REQUEST, "BANK_ACCOUNT_NOT_ON_FILE",
                    "Settlements are paid only to your verified bank account on file; omit bankAccountRef "
                            + "or send that account's id");
        }
        // Requirement 4.9: the destination is stored encrypted at rest. The account on file is
        // already ciphertext and is copied as is: encrypting it again would leave the settlement
        // holding a value that decrypts to ciphertext rather than to the account.
        Settlement settlement = Settlement.request(providerId, amount, profile.getBankAccountEncrypted());
        settlementRepository.save(settlement);

        // Reserve the funds by debiting the wallet; a failed transfer credits them back downstream.
        profile.debitWallet(amount);
        profileRepository.save(profile);
        return settlement;
    }

    /**
     * {@code true} when the request names the account on file: no reference supplied, or the
     * account's own id ({@code BankAccountResponse.id}, the profile id), which is what the provider
     * app sends when the provider picks their account. Any other reference is refused by
     * {@link #requestSettlement}.
     */
    private static boolean usesStoredAccount(ProviderProfile profile, String bankAccountRef) {
        return bankAccountRef == null
                || bankAccountRef.isBlank()
                || bankAccountRef.strip().equalsIgnoreCase(profile.getId().toString());
    }

    /**
     * Adds or replaces the provider's settlement bank account (Requirements 4.9, 14.2).
     *
     * <p>The details are normalised (whitespace stripped from the number, IFSC trimmed and
     * upper-cased, holder name trimmed) and validated, then stored as one encrypted value replacing
     * any previous account. Whether the new account is verified at once is decided by the
     * {@link BankAccountVerificationPort}: with the default manual adapter it is pending until an
     * administrator verifies it, so replacing a verified account withdraws settlement eligibility
     * until the new one is verified.
     *
     * @throws ProviderException 400 {@code VALIDATION_ERROR} with one detail per invalid field,
     *                           404 {@code PROVIDER_NOT_FOUND} when the provider has no profile
     */
    @Transactional
    public BankAccountView setBankAccount(UUID providerId, BankAccountCommand cmd) {
        BankAccountDetails details = validateBankAccount(cmd);
        ProviderProfile profile = getExisting(providerId);
        BankAccountVerificationPort.Outcome outcome = bankAccountVerification.verify(details);
        profile.setBankAccount(bankAccountCodec.seal(details),
                outcome == BankAccountVerificationPort.Outcome.VERIFIED);
        profileRepository.save(profile);
        log.info("Bank account for provider {} stored ({})", providerId, outcome);
        return bankAccountCodec.describe(profile.getBankAccountEncrypted(), profile.isBankAccountVerified());
    }

    /** The displayable form of the provider's bank account, empty when none is on file. */
    public Optional<BankAccountView> bankAccountOf(ProviderProfile profile) {
        return Optional.ofNullable(bankAccountCodec.describe(
                profile.getBankAccountEncrypted(), profile.isBankAccountVerified()));
    }

    /**
     * Normalises and validates a submitted bank account, reporting every invalid field at once in
     * the {@code field: message} form the bean-validation handler uses.
     */
    static BankAccountDetails validateBankAccount(BankAccountCommand cmd) {
        String holder = cmd.accountHolderName() == null ? null : cmd.accountHolderName().strip();
        String number = cmd.accountNumber() == null ? null : cmd.accountNumber().replaceAll("\\s", "");
        String ifsc = cmd.ifsc() == null ? null : cmd.ifsc().strip().toUpperCase(Locale.ROOT);

        List<String> errors = new ArrayList<>();
        if (holder == null || holder.length() < BankAccountDetails.HOLDER_NAME_MIN
                || holder.length() > BankAccountDetails.HOLDER_NAME_MAX) {
            errors.add("accountHolderName: must be between " + BankAccountDetails.HOLDER_NAME_MIN
                    + " and " + BankAccountDetails.HOLDER_NAME_MAX + " characters");
        }
        if (number == null || !BankAccountDetails.ACCOUNT_NUMBER.matcher(number).matches()) {
            errors.add("accountNumber: must be 9 to 18 digits");
        }
        if (ifsc == null || !BankAccountDetails.IFSC.matcher(ifsc).matches()) {
            errors.add("ifsc: must be 11 characters: 4 letters, the digit 0, then 6 letters or digits");
        }
        if (!errors.isEmpty()) {
            throw new ProviderException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Bank account details are invalid", errors);
        }
        return new BankAccountDetails(holder, number, ifsc);
    }

    // ================= Rating (Requirements 4.7, 15.4) =================

    /** Highest aggregate rating: reviews are 1&ndash;5 stars. */
    static final BigDecimal MAX_RATING = new BigDecimal("5");

    /**
     * Sets the provider's aggregate rating as computed by the Rating &amp; Review Service over the
     * provider's customer reviews ({@code PUT /internal/providers/{id}/rating}). If the aggregate
     * drops below {@code jihajoor.provider.review-rating-threshold} and the provider is not already
     * flagged, flags them UNDER_REVIEW (so dispatch skips them) and alerts Admin, once.
     *
     * <p>Idempotent: the aggregate is set, not accumulated, and an aggregate older than the one held
     * (a lower {@code sequence}) is ignored, so the sender's retries and out-of-order deliveries are
     * all safe. See {@link ProviderProfile#applyRatingAggregate}.
     *
     * <p>Checked here as well as on the request DTO, since this is the authoritative check for every
     * caller: {@code count >= 0}, {@code sequence >= 0}, and an {@code average} in 0&ndash;5 whenever
     * {@code count > 0}.
     *
     * @throws ProviderException 404 {@code PROVIDER_NOT_FOUND} for an unknown provider, 400
     *                           {@code VALIDATION_ERROR} for an out-of-range value
     */
    @Transactional
    public RatingApplication applyRatingAggregate(UUID providerId, BigDecimal average, int count,
                                                  long sequence) {
        validateRatingAggregate(average, count, sequence);
        ProviderProfile profile = getExisting(providerId);
        RatingUpdateOutcome outcome = profile.applyRatingAggregate(
                average, count, sequence, props.getReviewRatingThreshold());
        if (!outcome.applied()) {
            log.info("Ignored stale rating for provider {}: sequence {} is older than the {} held",
                    providerId, sequence, profile.getRatingSequence());
            return new RatingApplication(profile, outcome);
        }
        profileRepository.save(profile);
        if (outcome == RatingUpdateOutcome.FLAGGED_FOR_REVIEW) {
            adminAlert.providerFlaggedForReview(providerId, average);
        }
        return new RatingApplication(profile, outcome);
    }

    private static void validateRatingAggregate(BigDecimal average, int count, long sequence) {
        List<String> errors = new ArrayList<>();
        if (count < 0) {
            errors.add("count: must be 0 or more");
        }
        if (sequence < 0) {
            errors.add("sequence: must be 0 or more");
        }
        if (count > 0 && (average == null || average.signum() < 0 || average.compareTo(MAX_RATING) > 0)) {
            errors.add("average: must be between 0 and 5 when count is above 0");
        }
        if (!errors.isEmpty()) {
            throw new ProviderException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Rating aggregate is invalid", errors);
        }
    }

    /** The profile after a rating push and what the push did. */
    public record RatingApplication(ProviderProfile profile, RatingUpdateOutcome outcome) {
    }

    // ================= Reads =================

    @Transactional(readOnly = true)
    public ProviderProfile getProfile(UUID providerId) {
        return getExisting(providerId);
    }

    /**
     * Wallet balance plus the running total credited so far today (Requirement 14.1).
     *
     * <p>"Today" is the civil day in {@code jihajoor.provider.earnings-day-zone}, not a rolling
     * 24-hour window, so the figure resets at local midnight the way a provider expects. Only
     * {@link EarningType#JOB_CREDIT} rows count towards the job tally — fee, penalty and agency-share rows are
     * itemised separately in the ledger and would otherwise inflate the count.
     */
    @Transactional(readOnly = true)
    public EarningsSnapshot earningsSummary(UUID providerId) {
        ProviderProfile profile = getOrEmpty(providerId);
        Instant dayStart = LocalDate.now(ZoneId.of(props.getEarningsDayZone()))
                .atStartOfDay(ZoneId.of(props.getEarningsDayZone()))
                .toInstant();
        List<ProviderEarning> today =
                earningRepository.findByProviderIdAndCreditedAtGreaterThanEqual(providerId, dayStart);
        BigDecimal net = today.stream()
                .map(ProviderEarning::getNet)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int jobs = (int) today.stream()
                .filter(e -> e.getType() == EarningType.JOB_CREDIT)
                .count();
        return new EarningsSnapshot(profile.getWalletBalance(), net, jobs);
    }

    /** Settlement request history, newest first (Requirement 14.3). */
    @Transactional(readOnly = true)
    public List<Settlement> settlementHistory(UUID providerId) {
        return settlementRepository.findByProviderIdOrderByRequestedAtDesc(providerId);
    }

    /**
     * Wallet balance and the bank account on file for settlements (Requirement 14.2).
     *
     * <p>The profile carries at most one account today, so the list is empty or a single entry;
     * returning a list keeps the contract stable once multiple accounts are supported.
     */
    @Transactional(readOnly = true)
    public ProviderProfile settlementInfo(UUID providerId) {
        return getOrEmpty(providerId);
    }

    /** Wallet balance and today's credited total, as returned by {@link #earningsSummary(UUID)}. */
    public record EarningsSnapshot(BigDecimal walletBalance, BigDecimal todayNet, int todayJobCount) {
    }

    private ProviderProfile getExisting(UUID providerId) {
        return profileRepository.findById(providerId)
                .orElseThrow(() -> ProviderException.notFound("Provider " + providerId + " not found"));
    }

    /**
     * The provider's profile for a dashboard read, or an unsaved blank one when they have not
     * completed onboarding yet. A freshly registered provider has a zero wallet and no history —
     * that is an empty ledger, not a missing provider — and answering 404 left the provider app's
     * dashboard showing "provider not found" with no way forward. Writes still require a real
     * profile ({@link #getExisting}).
     */
    private ProviderProfile getOrEmpty(UUID providerId) {
        return profileRepository.findById(providerId)
                .orElseGet(() -> ProviderProfile.createWithId(providerId));
    }

    private ProviderProfile getOrCreate(UUID providerId) {
        // PUT /providers/{id}/profile is upsert semantics: {id} is the profile identity.
        return profileRepository.findById(providerId)
                .orElseGet(() -> ProviderProfile.createWithId(providerId));
    }
}
