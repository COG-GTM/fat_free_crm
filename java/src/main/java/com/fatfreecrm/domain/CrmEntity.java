package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;

/**
 * Columns shared by the five Rails "entity" models (Account, Campaign, Contact, Lead,
 * Opportunity): ownership, assignment, access level, soft delete and timestamps
 * (docs/migration/target-architecture.md §2.3).
 *
 * <p>{@code userId} / {@code assignedTo} are kept as plain FK ids (no JPA relationship to
 * {@code users}) in this phase.
 *
 * <p>{@code subscribedUsers} is opaque Rails-serialized YAML text (an Array of user ids written
 * by {@code serialize :subscribed_users}); see docs/migration/data-model.md "Serialized columns".
 * It is mapped as a raw {@link String} and must not be parsed here — conversion happens at
 * data-migration time.
 *
 * <p>Rails stores timestamps as {@code timestamp without time zone}, so {@link LocalDateTime}
 * is used for every datetime column.
 */
@MappedSuperclass
public abstract class CrmEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(name = "access", length = 8)
    private String access;

    @Column(name = "background_info")
    private String backgroundInfo;

    @Column(name = "subscribed_users", columnDefinition = "text")
    private String subscribedUsers;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(Long assignedTo) {
        this.assignedTo = assignedTo;
    }

    public String getAccess() {
        return access;
    }

    public void setAccess(String access) {
        this.access = access;
    }

    public String getBackgroundInfo() {
        return backgroundInfo;
    }

    public void setBackgroundInfo(String backgroundInfo) {
        this.backgroundInfo = backgroundInfo;
    }

    public String getSubscribedUsers() {
        return subscribedUsers;
    }

    public void setSubscribedUsers(String subscribedUsers) {
        this.subscribedUsers = subscribedUsers;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
