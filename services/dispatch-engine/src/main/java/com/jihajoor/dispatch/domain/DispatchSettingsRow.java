package com.jihajoor.dispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The saved dispatch rules: the one row of {@code dispatch.dispatch_settings} (Requirements 8.2,
 * 8.4, 8.5, 8.8, 19.5). See {@code JpaDispatchSettingsStore} for how it is read and written.
 *
 * <p>The weights are stored as the decimals of their {@code double} values
 * ({@link BigDecimal#valueOf(double)}, the shortest decimal that reads back as the same double), so
 * an Admin's {@code 0.30} is stored as {@code 0.3} and the database's exact "sums to 1" check sees
 * the same numbers {@link MatchingWeights#ofExact} will see when the row is read back. A weight set
 * whose decimals would not survive that trip (more digits than a {@code double} holds) is refused
 * by {@link #apply} rather than stored as a row that could not be loaded again.
 *
 * <p>Mirrors {@code V3__dispatch_settings.sql} exactly ({@code ddl-auto=validate}).
 */
@Entity
@Table(name = "dispatch_settings")
public class DispatchSettingsRow {

    /** The only id the table allows ({@code CHECK (id = 1)}). */
    public static final int SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private int id;

    @Column(name = "distance_weight", nullable = false)
    private BigDecimal distanceWeight;

    @Column(name = "availability_weight", nullable = false)
    private BigDecimal availabilityWeight;

    @Column(name = "rating_weight", nullable = false)
    private BigDecimal ratingWeight;

    @Column(name = "skill_weight", nullable = false)
    private BigDecimal skillWeight;

    @Column(name = "performance_weight", nullable = false)
    private BigDecimal performanceWeight;

    @Column(name = "initial_radius_km", nullable = false)
    private double initialRadiusKm;

    @Column(name = "radius_increment_km", nullable = false)
    private double radiusIncrementKm;

    @Column(name = "max_expansion_cycles", nullable = false)
    private int maxExpansionCycles;

    @Column(name = "offer_timeout_seconds", nullable = false)
    private long offerTimeoutSeconds;

    @Column(name = "offer_batch_size", nullable = false)
    private int offerBatchSize;

    @Column(name = "agency_fallback_minutes", nullable = false)
    private long agencyFallbackMinutes;

    /**
     * A wrapper so a row that has never been saved reads as new ({@code null}) to Spring Data, which
     * then persists it rather than merging it; Hibernate bumps it on every update.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected DispatchSettingsRow() {
        // JPA
    }

    /** The row for the first save. */
    public static DispatchSettingsRow first(DispatchSettings settings, Instant now, String updatedBy) {
        DispatchSettingsRow row = new DispatchSettingsRow();
        row.id = SINGLETON_ID;
        row.apply(settings, now, updatedBy);
        return row;
    }

    /**
     * Replaces every value with {@code settings} and records who saved it when.
     *
     * @throws WeightValidationException if the weights' stored decimals would no longer sum to
     *                                   exactly 1.0; the row is left unchanged
     */
    public void apply(DispatchSettings settings, Instant now, String updatedBy) {
        Objects.requireNonNull(settings, "settings must not be null");
        MatchingWeights w = settings.weights();
        BigDecimal distance = BigDecimal.valueOf(w.distanceWeight());
        BigDecimal availability = BigDecimal.valueOf(w.availabilityWeight());
        BigDecimal rating = BigDecimal.valueOf(w.ratingWeight());
        BigDecimal skill = BigDecimal.valueOf(w.skillWeight());
        BigDecimal performance = BigDecimal.valueOf(w.performanceWeight());
        // The row must load again: the same check the read applies, before anything is changed.
        MatchingWeights.ofExact(distance, availability, rating, skill, performance);

        this.distanceWeight = distance;
        this.availabilityWeight = availability;
        this.ratingWeight = rating;
        this.skillWeight = skill;
        this.performanceWeight = performance;
        this.initialRadiusKm = settings.initialRadiusKm();
        this.radiusIncrementKm = settings.radiusIncrementKm();
        this.maxExpansionCycles = settings.maxExpansionCycles();
        this.offerTimeoutSeconds = settings.offerTimeoutSeconds();
        this.offerBatchSize = settings.offerBatchSize();
        this.agencyFallbackMinutes = settings.agencyFallbackMinutes();
        this.updatedAt = now;
        this.updatedBy = updatedBy == null || updatedBy.length() <= 255 ? updatedBy : updatedBy.substring(0, 255);
    }

    /**
     * The saved rules as the domain value.
     *
     * @throws WeightValidationException if the stored weights are not a valid set (only possible
     *                                   if the row was edited by hand around the table's checks)
     */
    public DispatchSettings toSettings() {
        return new DispatchSettings(
                MatchingWeights.ofExact(distanceWeight, availabilityWeight, ratingWeight, skillWeight,
                        performanceWeight),
                initialRadiusKm,
                radiusIncrementKm,
                maxExpansionCycles,
                offerTimeoutSeconds,
                offerBatchSize,
                agencyFallbackMinutes);
    }

    public int getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
