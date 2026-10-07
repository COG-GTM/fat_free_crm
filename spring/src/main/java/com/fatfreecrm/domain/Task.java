package com.fatfreecrm.domain;

import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.domain.support.RailsModelTypeConverter;
import com.fatfreecrm.domain.support.SubscribedUsersConverter;
import com.fatfreecrm.domain.support.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "tasks")
@DynamicUpdate
@SQLRestriction("deleted_at IS NULL")
public class Task extends TimestampedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to", columnDefinition = "int4")
    private User assignedTo;

    public User getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(User assignedTo) {
        this.assignedTo = assignedTo;
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completed_by", columnDefinition = "int4")
    private User completedBy;

    public User getCompletedBy() {
        return completedBy;
    }

    public void setCompletedBy(User completedBy) {
        this.completedBy = completedBy;
    }

    @Convert(converter = RailsModelTypeConverter.class)
    @Column(name = "asset_type")
    private RailsModelType assetType;

    @Column(name = "asset_id")
    private Integer assetId;

    public RailsModelType getAssetType() {
        return assetType;
    }

    public void setAssetType(RailsModelType assetType) {
        this.assetType = assetType;
    }

    public Integer getAssetId() {
        return assetId;
    }

    public void setAssetId(Integer assetId) {
        this.assetId = assetId;
    }

    @Convert(converter = SubscribedUsersConverter.class)
    @Column(name = "subscribed_users")
    private List<Long> subscribedUsers = new ArrayList<>();

    public List<Long> getSubscribedUsers() {
        if (subscribedUsers == null) {
            subscribedUsers = new ArrayList<>();
        }
        return subscribedUsers;
    }

    public void setSubscribedUsers(List<Long> subscribedUsers) {
        this.subscribedUsers = subscribedUsers == null ? new ArrayList<>() : new ArrayList<>(subscribedUsers);
    }

    @Column(name = "name", nullable = false)
    private String name = "";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Column(name = "priority", length = 32)
    private String priority;

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    @Column(name = "category", length = 32)
    private String category;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    @Column(name = "bucket", length = 32)
    private String bucket;

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    @Column(name = "due_at")
    private Instant dueAt;

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    @Column(name = "completed_at")
    private Instant completedAt;

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    @Column(name = "background_info")
    private String backgroundInfo;

    public String getBackgroundInfo() {
        return backgroundInfo;
    }

    public void setBackgroundInfo(String backgroundInfo) {
        this.backgroundInfo = backgroundInfo;
    }

}
