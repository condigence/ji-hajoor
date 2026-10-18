package com.jihajoor.dispatch.adapter;

import com.jihajoor.dispatch.domain.DispatchSettings;
import com.jihajoor.dispatch.domain.DispatchSettingsRow;
import com.jihajoor.dispatch.domain.DispatchSettingsRowRepository;
import com.jihajoor.dispatch.domain.WeightValidationException;
import com.jihajoor.dispatch.port.DispatchSettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Default {@link DispatchSettingsStore}: the single {@code dispatch.dispatch_settings} row, shared
 * by every replica.
 *
 * <p>Concurrent saves are <em>last write wins</em>, serialised by a row lock: each save reads the
 * row with {@code SELECT ... FOR UPDATE}, applies its change to what it read and writes it back
 * before releasing the lock. The Admin API has no version or ETag for an optimistic "someone else
 * saved first" refusal, and the rules form is always submitted whole by the one role allowed to
 * change it (SUPER_ADMIN), so the later save simply wins; the lock makes sure a partial update —
 * the weights-only endpoint, or a form that omits the wave size — is applied on top of the latest
 * save instead of on a cached copy, so it never silently undoes another replica's change. Every
 * save records {@code updated_at} / {@code updated_by} and bumps {@code version} for audit.
 *
 * <p>Before the first save there is no row to lock, so two replicas making the very first save
 * at the same moment both try to insert it; the primary key lets one through and the other is
 * retried once, in a new transaction, in which it finds the row and updates it like any later save.
 */
@Component
public class JpaDispatchSettingsStore implements DispatchSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(JpaDispatchSettingsStore.class);

    private final DispatchSettingsRowRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JpaDispatchSettingsStore(DispatchSettingsRowRepository repository,
                                    PlatformTransactionManager transactionManager,
                                    Clock clock) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public Optional<DispatchSettings> load() {
        return repository.findById(DispatchSettingsRow.SINGLETON_ID).map(DispatchSettingsRow::toSettings);
    }

    @Override
    public DispatchSettings update(UnaryOperator<DispatchSettings> change, DispatchSettings unsaved,
                                   String updatedBy) {
        Objects.requireNonNull(change, "change must not be null");
        Objects.requireNonNull(unsaved, "unsaved must not be null");
        try {
            return transactions.execute(status -> updateOnce(change, unsaved, updatedBy));
        } catch (DataIntegrityViolationException firstSaveRace) {
            // Another replica inserted the row between our read and our insert; it exists now.
            log.debug("Dispatch settings were first saved concurrently elsewhere; retrying on top of that save: {}",
                    firstSaveRace.toString());
            return transactions.execute(status -> updateOnce(change, unsaved, updatedBy));
        }
    }

    private DispatchSettings updateOnce(UnaryOperator<DispatchSettings> change, DispatchSettings unsaved,
                                        String updatedBy) {
        Optional<DispatchSettingsRow> existing = repository.findByIdForUpdate(DispatchSettingsRow.SINGLETON_ID);
        DispatchSettings current = existing.map(row -> savedOrElse(row, unsaved)).orElse(unsaved);
        DispatchSettings next = Objects.requireNonNull(change.apply(current), "change returned null");
        Instant now = clock.instant();
        if (existing.isPresent()) {
            // Managed and locked: written (and its version bumped) when the transaction commits.
            existing.get().apply(next, now, updatedBy);
        } else {
            // Flushed now so a concurrent first save surfaces here, as DataIntegrityViolationException.
            repository.saveAndFlush(DispatchSettingsRow.first(next, now, updatedBy));
        }
        return next;
    }

    /**
     * The saved settings, or {@code unsaved} when the row holds a weight set that does not
     * validate (it can only have been edited by hand around the table's checks). Falling back
     * keeps the row correctable through the Admin API: a full save replaces it.
     */
    private static DispatchSettings savedOrElse(DispatchSettingsRow row, DispatchSettings unsaved) {
        try {
            return row.toSettings();
        } catch (WeightValidationException invalid) {
            log.warn("The saved dispatch settings hold invalid weights ({}); this update starts from the"
                    + " configured defaults instead", invalid.getMessage());
            return unsaved;
        }
    }
}
