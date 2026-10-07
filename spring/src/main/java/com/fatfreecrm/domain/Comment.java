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
 * Rails {@code Comment} on any commentable (core entities and tasks) via the
 * {@code commentable_type}/{@code commentable_id} pair.
 */
@Entity
@Table(name = "comments")
public class Comment extends TimestampedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    @Embedded
    @AttributeOverride(name = "type", column = @Column(name = "commentable_type"))
    @AttributeOverride(name = "id", column = @Column(name = "commentable_id", columnDefinition = "int4"))
    private PolymorphicRef commentable;

    @Column(name = "private")
    private Boolean privateComment;

    @Column(name = "title")
    private String title;

    @Column(name = "comment")
    private String comment;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public PolymorphicRef getCommentable() {
        return commentable;
    }

    public void setCommentable(PolymorphicRef commentable) {
        this.commentable = commentable;
    }

    public Boolean getPrivateComment() {
        return privateComment;
    }

    public void setPrivateComment(Boolean privateComment) {
        this.privateComment = privateComment;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
