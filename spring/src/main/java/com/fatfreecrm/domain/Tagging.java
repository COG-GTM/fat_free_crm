package com.fatfreecrm.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * acts-as-taggable-on {@code Tagging}: links a {@link Tag} to a taggable entity, optionally recording who tagged it.
 */
@Entity
@Table(name = "taggings")
public class Tagging extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", columnDefinition = "int4")
    private Tag tag;

    @Embedded
    @AttributeOverride(name = "type", column = @Column(name = "taggable_type", length = 50))
    @AttributeOverride(name = "id", column = @Column(name = "taggable_id", columnDefinition = "int4"))
    private PolymorphicRef taggable;

    @Embedded
    @AttributeOverride(name = "type", column = @Column(name = "tagger_type"))
    @AttributeOverride(name = "id", column = @Column(name = "tagger_id", columnDefinition = "int4"))
    private PolymorphicRef tagger;

    @Column(name = "context", length = 50)
    private String context;

    @Column(name = "created_at")
    private Instant createdAt;

    public Tag getTag() {
        return tag;
    }

    public void setTag(Tag tag) {
        this.tag = tag;
    }

    public PolymorphicRef getTaggable() {
        return taggable;
    }

    public void setTaggable(PolymorphicRef taggable) {
        this.taggable = taggable;
    }

    public PolymorphicRef getTagger() {
        return tagger;
    }

    public void setTagger(PolymorphicRef tagger) {
        this.tagger = tagger;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @PrePersist
    void stampCreation() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
