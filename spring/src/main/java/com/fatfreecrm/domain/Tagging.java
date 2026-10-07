package com.fatfreecrm.domain;

import com.fatfreecrm.domain.support.BaseEntity;
import com.fatfreecrm.domain.support.RailsModelType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
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

    @Column(name = "taggable_type")
    private String taggableType;

    @Column(name = "taggable_id")
    private Integer taggableId;

    public String getTaggableType() {
        return taggableType;
    }

    public void setTaggableType(String taggableType) {
        this.taggableType = taggableType;
    }

    public Optional<RailsModelType> taggableModelType() {
        return RailsModelType.fromRailsName(taggableType);
    }

    public void setTaggableModelType(RailsModelType type) {
        taggableType = type == null ? null : type.railsName();
    }

    public Integer getTaggableId() {
        return taggableId;
    }

    public void setTaggableId(Integer taggableId) {
        this.taggableId = taggableId;
    }

    @Column(name = "tagger_type")
    private String taggerType;

    @Column(name = "tagger_id")
    private Integer taggerId;

    public String getTaggerType() {
        return taggerType;
    }

    public void setTaggerType(String taggerType) {
        this.taggerType = taggerType;
    }

    public Optional<RailsModelType> taggerModelType() {
        return RailsModelType.fromRailsName(taggerType);
    }

    public void setTaggerModelType(RailsModelType type) {
        taggerType = type == null ? null : type.railsName();
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
