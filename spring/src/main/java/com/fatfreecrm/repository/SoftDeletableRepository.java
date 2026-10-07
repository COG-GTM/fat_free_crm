package com.fatfreecrm.repository;

import com.fatfreecrm.domain.BaseEntity;
import com.fatfreecrm.domain.SoftDeletable;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Base repository for tables Rails soft-deletes. Reads inherit the entity's
 * {@code @SQLRestriction("deleted_at IS NULL")}, so {@code findById} on a deleted row is empty, and
 * {@link #softDelete} is the only supported way to "delete": the physical {@code delete*} methods inherited from
 * {@link JpaRepository} must not be used on these tables while Rails still reads them.
 */
@NoRepositoryBean
public interface SoftDeletableRepository<T extends BaseEntity & SoftDeletable> extends JpaRepository<T, Long> {

    /**
     * Stamps {@code deleted_at} on a live row and returns 1, or 0 if the row is missing or already deleted.
     * Runs in its own write transaction when no caller transaction is active.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update #{#entityName} e set e.deletedAt = :deletedAt where e.id = :id and e.deletedAt is null")
    int softDelete(@Param("id") Long id, @Param("deletedAt") Instant deletedAt);

    default int softDelete(Long id) {
        return softDelete(id, Instant.now());
    }
}
