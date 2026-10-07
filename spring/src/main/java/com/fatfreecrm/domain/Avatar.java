package com.fatfreecrm.domain;

import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.domain.support.RailsModelTypeConverter;
import com.fatfreecrm.domain.support.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "avatars")
@DynamicUpdate
public class Avatar extends TimestampedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    @Convert(converter = RailsModelTypeConverter.class)
    @Column(name = "entity_type")
    private RailsModelType entityType;

    @Column(name = "entity_id")
    private Integer entityId;

    public RailsModelType getEntityType() {
        return entityType;
    }

    public void setEntityType(RailsModelType entityType) {
        this.entityType = entityType;
    }

    public Integer getEntityId() {
        return entityId;
    }

    public void setEntityId(Integer entityId) {
        this.entityId = entityId;
    }

    @Column(name = "image_file_size")
    private Integer imageFileSize;

    public Integer getImageFileSize() {
        return imageFileSize;
    }

    public void setImageFileSize(Integer imageFileSize) {
        this.imageFileSize = imageFileSize;
    }

    @Column(name = "image_file_name")
    private String imageFileName;

    public String getImageFileName() {
        return imageFileName;
    }

    public void setImageFileName(String imageFileName) {
        this.imageFileName = imageFileName;
    }

    @Column(name = "image_content_type")
    private String imageContentType;

    public String getImageContentType() {
        return imageContentType;
    }

    public void setImageContentType(String imageContentType) {
        this.imageContentType = imageContentType;
    }

}
