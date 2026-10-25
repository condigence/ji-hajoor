package com.jihajoor.dispatch.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Persistence for {@link DispatchSettingsRow}. */
public interface DispatchSettingsRowRepository extends JpaRepository<DispatchSettingsRow, Integer> {

    /**
     * Reads the row and holds a row lock ({@code SELECT ... FOR UPDATE}) until the transaction
     * ends, so concurrent saves — from any replica — apply one after another, each on top of the
     * last. Finds nothing, and locks nothing, before the first save.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DispatchSettingsRow s where s.id = :id")
    Optional<DispatchSettingsRow> findByIdForUpdate(@Param("id") int id);
}
