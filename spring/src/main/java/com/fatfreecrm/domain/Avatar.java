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
 * Rails {@code Avatar}: image metadata for a user or contact. The binary lives in Active Storage, which stays
 * Rails-owned; only the legacy Paperclip metadata columns are mapped here.
 */
@Entity
@Table(name = "avatars")
public class Avatar extends TimestampedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    @Embedded
    @AttributeOverride(name = "type", column = @Column(name = "entity_type"))
    @AttributeOverride(name = "id", column = @Column(name = "entity_id", columnDefinition = "int4"))
    private PolymorphicRef entity;

    @Column(name = "image_file_size")
    private Integer imageFileSize;

    @Column(name = "image_file_name")
    private String imageFileName;

    @Column(name = "image_content_type")
    private String imageContentType;

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public PolymorphicRef getEntity() {
        return entity;
    }

    public void setEntity(PolymorphicRef entity) {
        this.entity = entity;
    }

    public Integer getImageFileSize() {
        return imageFileSize;
    }

    public void setImageFileSize(Integer imageFileSize) {
        this.imageFileSize = imageFileSize;
    }

    public String getImageFileName() {
        return imageFileName;
    }

    public void setImageFileName(String imageFileName) {
        this.imageFileName = imageFileName;
    }

    public String getImageContentType() {
        return imageContentType;
    }

    public void setImageContentType(String imageContentType) {
        this.imageContentType = imageContentType;
    }
}
