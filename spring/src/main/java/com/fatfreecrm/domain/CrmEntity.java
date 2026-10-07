package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Columns shared by the five core CRM entities (Account, Campaign, Contact, Lead, Opportunity): owner, assignee,
 * access level, soft delete, free-form background and the YAML-serialised subscriber list.
 *
 * <p>This is a {@code @MappedSuperclass}, not JPA inheritance: each subclass maps its own Rails table and no
 * discriminator or join is introduced into the shared schema.</p>
 */
@MappedSuperclass
public abstract class CrmEntity extends TimestampedEntity implements SoftDeletable {

    public static final String ACCESS_PUBLIC = "Public";
    public static final String ACCESS_PRIVATE = "Private";
    public static final String ACCESS_SHARED = "Shared";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to", columnDefinition = "int4")
    private User assignedTo;

    @Column(name = "access", length = 8)
    private String access;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "background_info")
    private String backgroundInfo;

    @Convert(converter = RailsYamlIdListConverter.class)
    @Column(name = "subscribed_users")
    private List<Long> subscribedUsers;

    /** The Rails class name stored in {@code *_type} discriminator columns that point at this entity. */
    public abstract String railsType();

    public PolymorphicRef toRef() {
        return PolymorphicRef.of(railsType(), getId());
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public User getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(User assignedTo) {
        this.assignedTo = assignedTo;
    }

    public String getAccess() {
        return access;
    }

    public void setAccess(String access) {
        this.access = access;
    }

    @Override
    public Instant getDeletedAt() {
        return deletedAt;
    }

    @Override
    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getBackgroundInfo() {
        return backgroundInfo;
    }

    public void setBackgroundInfo(String backgroundInfo) {
        this.backgroundInfo = backgroundInfo;
    }

    /**
     * Ids of users subscribed to change notifications. Rails stores {@code NULL} for rows that never had a
     * subscriber list and {@code --- []} for an explicitly empty one; both read as an empty list here while the
     * stored representation is preserved untouched until the list is actually modified.
     */
    public List<Long> getSubscribedUsers() {
        return subscribedUsers == null ? List.of() : Collections.unmodifiableList(subscribedUsers);
    }

    public void setSubscribedUsers(List<Long> subscribedUsers) {
        this.subscribedUsers = subscribedUsers == null ? null : new ArrayList<>(subscribedUsers);
    }

    public boolean subscribe(Long userId) {
        if (subscribedUsers == null) {
            subscribedUsers = new ArrayList<>();
        }
        if (subscribedUsers.contains(userId)) {
            return false;
        }
        return subscribedUsers.add(userId);
    }

    public boolean unsubscribe(Long userId) {
        return subscribedUsers != null && subscribedUsers.remove(userId);
    }
}
