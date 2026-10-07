package com.fatfreecrm.domain;

import com.fatfreecrm.domain.support.BaseEntity;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.domain.support.RailsModelTypeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "versions")
@DynamicUpdate
public class Version extends BaseEntity {

    @Convert(converter = RailsModelTypeConverter.class)
    @Column(name = "item_type", nullable = false)
    private RailsModelType itemType;

    @Column(name = "item_id", nullable = false)
    private Integer itemId;

    public RailsModelType getItemType() {
        return itemType;
    }

    public void setItemType(RailsModelType itemType) {
        this.itemType = itemType;
    }

    public Integer getItemId() {
        return itemId;
    }

    public void setItemId(Integer itemId) {
        this.itemId = itemId;
    }

    @Convert(converter = RailsModelTypeConverter.class)
    @Column(name = "related_type")
    private RailsModelType relatedType;

    @Column(name = "related_id")
    private Integer relatedId;

    public RailsModelType getRelatedType() {
        return relatedType;
    }

    public void setRelatedType(RailsModelType relatedType) {
        this.relatedType = relatedType;
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
