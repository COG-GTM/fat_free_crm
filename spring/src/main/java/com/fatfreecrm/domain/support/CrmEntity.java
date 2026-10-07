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

@MappedSuperclass
public abstract class CrmEntity extends TimestampedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to", columnDefinition = "int4")
    private User assignedTo;

    @Convert(converter = AccessConverter.class)
    @Column(name = "access")
    private Access access = Access.PUBLIC;

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

    public Access getAccess() {
        return access;
    }

    public void setAccess(Access access) {
        this.access = access;
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
