package com.fatfreecrm.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Rails {@code Permission}: grants a user or a group access to a {@code Shared} asset
 * ({@code asset_type}/{@code asset_id} polymorphic pair).
 */
@Entity
@Table(name = "permissions")
public class Permission extends TimestampedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", columnDefinition = "int4")
    private Group group;

    @Embedded
    @AttributeOverride(name = "type", column = @Column(name = "asset_type"))
    @AttributeOverride(name = "id", column = @Column(name = "asset_id", columnDefinition = "int4"))
    private PolymorphicRef asset;

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Group getGroup() {
        return group;
    }

    public void setGroup(Group group) {
        this.group = group;
    }

    public PolymorphicRef getAsset() {
        return asset;
    }

    public void setAsset(PolymorphicRef asset) {
        this.asset = asset;
    }
}
