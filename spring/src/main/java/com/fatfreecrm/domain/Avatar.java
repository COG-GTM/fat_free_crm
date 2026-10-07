package com.fatfreecrm.domain;

import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.domain.support.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Optional;
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

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private Integer entityId;

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public Optional<RailsModelType> entityModelType() {
        return RailsModelType.fromRailsName(entityType);
    }

    public void setEntityModelType(RailsModelType type) {
        entityType = type == null ? null : type.railsName();
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
