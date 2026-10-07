package com.fatfreecrm.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * PaperTrail {@code Version} audit row. Rails owns this history, so the mapping is read-only ({@code @Immutable});
 * {@code object}/{@code object_changes} are Rails YAML snapshots kept opaque. {@code whodunnit} is the acting user's
 * id stored as text by PaperTrail.
 */
@Entity
@Table(name = "versions")
@Immutable
public class Version extends BaseEntity {

    @Embedded
    @AttributeOverride(name = "type", column = @Column(name = "item_type"))
    @AttributeOverride(name = "id", column = @Column(name = "item_id", columnDefinition = "int4"))
    private PolymorphicRef item;

    @Column(name = "event", length = 512)
    private String event;

    @Column(name = "whodunnit")
    private String whodunnit;

    @Column(name = "object")
    private String serializedObject;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "object_changes")
    private String objectChanges;

    @Embedded
    @AttributeOverride(name = "type", column = @Column(name = "related_type"))
    @AttributeOverride(name = "id", column = @Column(name = "related_id", columnDefinition = "int4"))
    private PolymorphicRef related;

    @Column(name = "transaction_id")
    private Integer transactionId;

    public PolymorphicRef getItem() {
        return item;
    }

    public void setItem(PolymorphicRef item) {
        this.item = item;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getWhodunnit() {
        return whodunnit;
    }

    public void setWhodunnit(String whodunnit) {
        this.whodunnit = whodunnit;
    }

    public String getSerializedObject() {
        return serializedObject;
    }

    public void setSerializedObject(String serializedObject) {
        this.serializedObject = serializedObject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getObjectChanges() {
        return objectChanges;
    }

    public void setObjectChanges(String objectChanges) {
        this.objectChanges = objectChanges;
    }

    public PolymorphicRef getRelated() {
        return related;
    }

    public void setRelated(PolymorphicRef related) {
        this.related = related;
    }

    public Integer getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(Integer transactionId) {
        this.transactionId = transactionId;
    }
}
