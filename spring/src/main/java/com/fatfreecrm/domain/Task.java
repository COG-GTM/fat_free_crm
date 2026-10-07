package com.fatfreecrm.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.SQLRestriction;

/**
 * Rails {@code Task}: a to-do optionally attached to any core entity through the {@code asset_type}/{@code asset_id}
 * pair. Tasks share owner/assignee/soft-delete/subscriber columns with {@link CrmEntity} but have no {@code access}
 * column, so they are mapped independently.
 */
@Entity
@Table(name = "tasks")
@SQLRestriction("deleted_at IS NULL")
public class Task extends TimestampedEntity implements SoftDeletable {

    public static final String RAILS_TYPE = "Task";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to", columnDefinition = "int4")
    private User assignedTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completed_by", columnDefinition = "int4")
    private User completedBy;

    @Column(name = "name", nullable = false)
    private String name;

    @Embedded
    @AttributeOverride(name = "type", column = @Column(name = "asset_type"))
    @AttributeOverride(name = "id", column = @Column(name = "asset_id", columnDefinition = "int4"))
    private PolymorphicRef asset;

    @Column(name = "priority", length = 32)
    private String priority;

    @Column(name = "category", length = 32)
    private String category;

    @Column(name = "bucket", length = 32)
    private String bucket;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "background_info")
    private String backgroundInfo;

    @Convert(converter = RailsYamlIdListConverter.class)
    @Column(name = "subscribed_users")
    private List<Long> subscribedUsers;

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

    public User getCompletedBy() {
        return completedBy;
    }

    public void setCompletedBy(User completedBy) {
        this.completedBy = completedBy;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public PolymorphicRef getAsset() {
        return asset;
    }

    public void setAsset(PolymorphicRef asset) {
        this.asset = asset;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
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

    public List<Long> getSubscribedUsers() {
        return subscribedUsers;
    }

    public void setSubscribedUsers(List<Long> subscribedUsers) {
        this.subscribedUsers = subscribedUsers;
    }

    public PolymorphicRef toRef() {
        return PolymorphicRef.of(RAILS_TYPE, getId());
    }

    public boolean isCompleted() {
        return completedAt != null;
    }
}
