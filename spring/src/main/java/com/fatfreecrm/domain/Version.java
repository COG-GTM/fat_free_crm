package com.fatfreecrm.domain;

import com.fatfreecrm.domain.support.BaseEntity;
import com.fatfreecrm.domain.support.RailsModelType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "versions")
@DynamicUpdate
public class Version extends BaseEntity {

    @Column(name = "item_type", nullable = false)
    private String itemType;

    @Column(name = "item_id", nullable = false)
    private Integer itemId;

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public Optional<RailsModelType> itemModelType() {
        return RailsModelType.fromRailsName(itemType);
    }

    public void setItemModelType(RailsModelType type) {
        itemType = type == null ? null : type.railsName();
    }

    public Integer getItemId() {
        return itemId;
    }

    public void setItemId(Integer itemId) {
        this.itemId = itemId;
    }

    @Column(name = "related_type")
    private String relatedType;

    @Column(name = "related_id")
    private Integer relatedId;

    public String getRelatedType() {
        return relatedType;
    }

    public void setRelatedType(String relatedType) {
        this.relatedType = relatedType;
    }

    public Optional<RailsModelType> relatedModelType() {
        return RailsModelType.fromRailsName(relatedType);
    }

    public void setRelatedModelType(RailsModelType type) {
        relatedType = type == null ? null : type.railsName();
    }

    public Integer getRelatedId() {
        return relatedId;
    }

    public void setRelatedId(Integer relatedId) {
        this.relatedId = relatedId;
    }

    @Column(name = "event", length = 512)
    private String event;

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    @Column(name = "whodunnit")
    private String whodunnit;

    public String getWhodunnit() {
        return whodunnit;
    }

    public void setWhodunnit(String whodunnit) {
        this.whodunnit = whodunnit;
    }

    @Column(name = "object")
    private String object;

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    @Column(name = "object_changes")
    private String objectChanges;

    public String getObjectChanges() {
        return objectChanges;
    }

    public void setObjectChanges(String objectChanges) {
        this.objectChanges = objectChanges;
    }

    @Column(name = "transaction_id")
    private Integer transactionId;

    public Integer getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(Integer transactionId) {
        this.transactionId = transactionId;
    }

    @Column(name = "created_at")
    private Instant createdAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

}
