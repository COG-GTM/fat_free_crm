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
@Table(name = "activities")
@DynamicUpdate
public class Activity extends TimestampedEntity {

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
    @Column(name = "subject_type")
    private RailsModelType subjectType;

    @Column(name = "subject_id")
    private Integer subjectId;

    public RailsModelType getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(RailsModelType subjectType) {
        this.subjectType = subjectType;
    }

    public Integer getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Integer subjectId) {
        this.subjectId = subjectId;
    }

    @Column(name = "action", length = 32)
    private String action = "created";

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    @Column(name = "info")
    private String info = "";

    public String getInfo() {
        return info;
    }

    public void setInfo(String info) {
        this.info = info;
    }

    @Column(name = "private")
    private Boolean privateFlag = false;

    public Boolean getPrivate() {
        return privateFlag;
    }

    public void setPrivate(Boolean privateFlag) {
        this.privateFlag = privateFlag;
    }

}
