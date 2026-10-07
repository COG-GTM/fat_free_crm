package com.fatfreecrm.domain.support;

import com.fatfreecrm.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@MappedSuperclass
public abstract class CrmEntity extends TimestampedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to", columnDefinition = "int4")
    private User assignedTo;

    @Column(name = "access")
    private String access = Access.PUBLIC.railsValue();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Convert(converter = SubscribedUsersConverter.class)
    @Column(name = "subscribed_users")
    private List<Long> subscribedUsers = new ArrayList<>();

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

    public Optional<Access> accessLevel() {
        return Access.fromRailsValue(access);
    }

    public void setAccessLevel(Access access) {
        this.access = access == null ? null : access.railsValue();
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public List<Long> getSubscribedUsers() {
        if (subscribedUsers == null) {
            subscribedUsers = new ArrayList<>();
        }
        return subscribedUsers;
    }

    public void setSubscribedUsers(List<Long> subscribedUsers) {
        this.subscribedUsers = subscribedUsers == null ? new ArrayList<>() : new ArrayList<>(subscribedUsers);
    }
}
