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
 * Legacy Rails {@code activities} table. Rails replaced it with PaperTrail {@link Version}s and no longer ships a
 * model for it, but the table (and any historical rows) is still part of the shared schema.
 */
@Entity
@Table(name = "activities")
public class Activity extends TimestampedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    @Embedded
    @AttributeOverride(name = "type", column = @Column(name = "subject_type"))
    @AttributeOverride(name = "id", column = @Column(name = "subject_id", columnDefinition = "int4"))
    private PolymorphicRef subject;

    @Column(name = "action", length = 32)
    private String action;

    @Column(name = "info")
    private String info;

    @Column(name = "private")
    private Boolean privateActivity;

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public PolymorphicRef getSubject() {
        return subject;
    }

    public void setSubject(PolymorphicRef subject) {
        this.subject = subject;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getInfo() {
        return info;
    }

    public void setInfo(String info) {
        this.info = info;
    }

    public Boolean getPrivateActivity() {
        return privateActivity;
    }

    public void setPrivateActivity(Boolean privateActivity) {
        this.privateActivity = privateActivity;
    }
}
