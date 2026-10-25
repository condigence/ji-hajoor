package com.jihajoor.dispatch.adapter;

import com.jihajoor.dispatch.domain.DispatchSettings;
import com.jihajoor.dispatch.domain.DispatchSettingsRow;
import com.jihajoor.dispatch.domain.DispatchSettingsRowRepository;
import com.jihajoor.dispatch.domain.MatchingWeights;
import com.jihajoor.dispatch.domain.WeightValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The settings store reads the single {@code dispatch_settings} row, inserts it on the first save,
 * updates the locked row in place afterwards — applying each change to what is saved, not to a
 * cached copy — records who saved it, and retries a first save that raced another replica's.
 */
class JpaDispatchSettingsStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final DispatchSettings DEFAULTS =
            new DispatchSettings(MatchingWeights.DEFAULT, 10.0, 5.0, 3, 60, 3, 5);
    private static final DispatchSettings SAVED =
            new DispatchSettings(MatchingWeights.of(0.40, 0.20, 0.20, 0.10, 0.10), 12.0, 3.0, 4, 90, 7, 10);

    private DispatchSettingsRowRepository repository;
    private PlatformTransactionManager transactionManager;
    private JpaDispatchSettingsStore store;

    @BeforeEach
    void setUp() {
        repository = mock(DispatchSettingsRowRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        store = new JpaDispatchSettingsStore(repository, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DispatchSettingsRow savedRow() {
        return DispatchSettingsRow.first(SAVED, NOW.minusSeconds(3600), "earlier-admin");
    }

    @Test
    void loadIsEmptyBeforeTheFirstSave() {
        when(repository.findById(DispatchSettingsRow.SINGLETON_ID)).thenReturn(Optional.empty());

        assertThat(store.load()).isEmpty();
    }

    @Test
    void loadReturnsTheSavedRules() {
        when(repository.findById(DispatchSettingsRow.SINGLETON_ID)).thenReturn(Optional.of(savedRow()));

        assertThat(store.load()).contains(SAVED);
    }

    @Test
    void theFirstSaveInsertsTheRowStartingFromTheDefaults() {
        when(repository.findByIdForUpdate(DispatchSettingsRow.SINGLETON_ID)).thenReturn(Optional.empty());
        List<DispatchSettings> seen = new ArrayList<>();

        DispatchSettings result = store.update(current -> {
            seen.add(current);
            return current.withOfferBatchSize(4);
        }, DEFAULTS, "admin-1");

        assertThat(seen).containsExactly(DEFAULTS);
        assertThat(result).isEqualTo(DEFAULTS.withOfferBatchSize(4));
        ArgumentCaptor<DispatchSettingsRow> inserted = ArgumentCaptor.forClass(DispatchSettingsRow.class);
        verify(repository).saveAndFlush(inserted.capture());
        DispatchSettingsRow row = inserted.getValue();
        assertThat(row.getId()).isEqualTo(DispatchSettingsRow.SINGLETON_ID);
        assertThat(row.toSettings()).isEqualTo(DEFAULTS.withOfferBatchSize(4));
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        assertThat(row.getUpdatedBy()).isEqualTo("admin-1");
        // New to JPA: persisted, not merged.
        assertThat(row.getVersion()).isNull();
    }

    @Test
    void aLaterSaveUpdatesTheLockedRowOnTopOfWhatIsSaved() {
        DispatchSettingsRow row = savedRow();
        when(repository.findByIdForUpdate(DispatchSettingsRow.SINGLETON_ID)).thenReturn(Optional.of(row));
        MatchingWeights weights = MatchingWeights.of(0.20, 0.20, 0.20, 0.20, 0.20);

        DispatchSettings result = store.update(current -> current.withWeights(weights), DEFAULTS, "admin-2");

        // The saved wave size and fallback are kept, not the defaults.
        assertThat(result).isEqualTo(SAVED.withWeights(weights));
        assertThat(row.toSettings()).isEqualTo(SAVED.withWeights(weights));
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        assertThat(row.getUpdatedBy()).isEqualTo("admin-2");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aFirstSaveThatRacedAnotherReplicaIsRetriedOnTopOfThatSave() {
        DispatchSettingsRow theirs = savedRow();
        when(repository.findByIdForUpdate(DispatchSettingsRow.SINGLETON_ID))
                .thenReturn(Optional.empty(), Optional.of(theirs));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        DispatchSettings result = store.update(current -> current.withAgencyFallbackMinutes(12), DEFAULTS,
                "admin-1");

        assertThat(result).isEqualTo(SAVED.withAgencyFallbackMinutes(12));
        assertThat(theirs.toSettings()).isEqualTo(SAVED.withAgencyFallbackMinutes(12));
        // Each attempt in a transaction of its own.
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager).rollback(any());
    }

    @Test
    void aRefusedChangeWritesNothing() {
        DispatchSettingsRow row = savedRow();
        when(repository.findByIdForUpdate(DispatchSettingsRow.SINGLETON_ID)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> store.update(current -> {
            throw new WeightValidationException("weights must sum to exactly 1.0 but summed to 1.5");
        }, DEFAULTS, "admin-1")).isInstanceOf(WeightValidationException.class);

        assertThat(row.toSettings()).isEqualTo(SAVED);
        assertThat(row.getUpdatedBy()).isEqualTo("earlier-admin");
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void weightsWhoseDecimalsWouldNotLoadAgainAreRefused() {
        when(repository.findByIdForUpdate(DispatchSettingsRow.SINGLETON_ID)).thenReturn(Optional.empty());
        // Sums to exactly 1, but with more digits than a double holds: each third becomes the double
        // 0.3333333333333333, the stored decimals would sum to 0.9999999999999999, and the row
        // could not be read back.
        MatchingWeights tooPrecise = MatchingWeights.ofExact(
                new BigDecimal("0.333333333333333333333"), new BigDecimal("0.333333333333333333333"),
                new BigDecimal("0.333333333333333333334"), BigDecimal.ZERO, BigDecimal.ZERO);

        assertThatThrownBy(() -> store.update(current -> current.withWeights(tooPrecise), DEFAULTS, "admin-1"))
                .isInstanceOf(WeightValidationException.class);

        verify(repository, never()).saveAndFlush(any());
    }
}
