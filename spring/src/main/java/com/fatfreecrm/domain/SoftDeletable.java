package com.fatfreecrm.domain;

import java.time.Instant;

/**
 * Rails paranoia-style soft delete: rows are never removed, {@code deleted_at} is stamped instead. Entities that
 * implement this also carry {@code @SQLRestriction("deleted_at IS NULL")} so queries only see live rows.
 */
public interface SoftDeletable {

    Instant getDeletedAt();

    void setDeletedAt(Instant deletedAt);

    default boolean isDeleted() {
        return getDeletedAt() != null;
    }
}
