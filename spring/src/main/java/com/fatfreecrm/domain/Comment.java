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
@Table(name = "comments")
@DynamicUpdate
public class Comment extends TimestampedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    @Column(name = "commentable_type")
    private String commentableType;

    @Column(name = "commentable_id")
    private Integer commentableId;

    public String getCommentableType() {
        return commentableType;
    }

    public void setCommentableType(String commentableType) {
        this.commentableType = commentableType;
    }

    public Optional<RailsModelType> commentableModelType() {
        return RailsModelType.fromRailsName(commentableType);
    }

    public void setCommentableModelType(RailsModelType type) {
        commentableType = type == null ? null : type.railsName();
    }

    public Integer getCommentableId() {
        return commentableId;
    }

    public void setCommentableId(Integer commentableId) {
        this.commentableId = commentableId;
    }

    @Column(name = "private")
    private Boolean privateFlag;

    public Boolean getPrivate() {
        return privateFlag;
    }

    public void setPrivate(Boolean privateFlag) {
        this.privateFlag = privateFlag;
    }

    @Column(name = "title")
    private String title = "";

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    @Column(name = "comment")
    private String comment;

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    @Column(name = "state", length = 16, nullable = false)
    private String state = "Expanded";

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

}
