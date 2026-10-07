package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;

/**
 * Rails {@code created_at}/{@code updated_at} semantics: both are set by the application, not the database.
 * Values that are already present (for example rows imported from Rails) are preserved so a round trip never
 * rewrites history; only genuinely dirty updates bump {@code updated_at}.
 */
@MappedSuperclass
public abstract class TimestampedEntity extends BaseEntity {

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @PrePersist
    void stampCreation() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void stampUpdate() {
        updatedAt = Instant.now();
    }
}
