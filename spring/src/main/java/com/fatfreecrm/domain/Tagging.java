package com.fatfreecrm.domain;

import com.fatfreecrm.domain.support.BaseEntity;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.domain.support.RailsModelTypeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "taggings")
@DynamicUpdate
public class Tagging extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", columnDefinition = "int4")
    private Tag tag;

    public Tag getTag() {
        return tag;
    }

    public void setTag(Tag tag) {
        this.tag = tag;
    }

    @Convert(converter = RailsModelTypeConverter.class)
    @Column(name = "taggable_type")
    private RailsModelType taggableType;

    @Column(name = "taggable_id")
    private Integer taggableId;

    public RailsModelType getTaggableType() {
        return taggableType;
    }

    public void setTaggableType(RailsModelType taggableType) {
        this.taggableType = taggableType;
    }

    public Integer getTaggableId() {
        return taggableId;
    }

    public void setTaggableId(Integer taggableId) {
        this.taggableId = taggableId;
    }

    @Convert(converter = RailsModelTypeConverter.class)
    @Column(name = "tagger_type")
    private RailsModelType taggerType;

    @Column(name = "tagger_id")
    private Integer taggerId;

    public RailsModelType getTaggerType() {
        return taggerType;
    }

    public void setTaggerType(RailsModelType taggerType) {
        this.taggerType = taggerType;
    }

    public Integer getTaggerId() {
        return taggerId;
    }

    public void setTaggerId(Integer taggerId) {
        this.taggerId = taggerId;
    }

    @Column(name = "context", length = 50)
    private String context;

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
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
