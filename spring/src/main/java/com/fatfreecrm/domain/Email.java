package com.fatfreecrm.domain;

import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.domain.support.TimestampedEntity;
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
@Table(name = "emails")
@DynamicUpdate
public class Email extends TimestampedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    @Column(name = "mediator_type")
    private String mediatorType;

    @Column(name = "mediator_id")
    private Integer mediatorId;

    public String getMediatorType() {
        return mediatorType;
    }

    public void setMediatorType(String mediatorType) {
        this.mediatorType = mediatorType;
    }

    public Optional<RailsModelType> mediatorModelType() {
        return RailsModelType.fromRailsName(mediatorType);
    }

    public void setMediatorModelType(RailsModelType type) {
        mediatorType = type == null ? null : type.railsName();
    }

    public Integer getMediatorId() {
        return mediatorId;
    }

    public void setMediatorId(Integer mediatorId) {
        this.mediatorId = mediatorId;
    }

    @Column(name = "imap_message_id", nullable = false)
    private String imapMessageId;

    public String getImapMessageId() {
        return imapMessageId;
    }

    public void setImapMessageId(String imapMessageId) {
        this.imapMessageId = imapMessageId;
    }

    @Column(name = "sent_from", nullable = false)
    private String sentFrom;

    public String getSentFrom() {
        return sentFrom;
    }

    public void setSentFrom(String sentFrom) {
        this.sentFrom = sentFrom;
    }

    @Column(name = "sent_to", nullable = false)
    private String sentTo;

    public String getSentTo() {
        return sentTo;
    }

    public void setSentTo(String sentTo) {
        this.sentTo = sentTo;
    }

    @Column(name = "cc")
    private String cc;

    public String getCc() {
        return cc;
    }

    public void setCc(String cc) {
        this.cc = cc;
    }

    @Column(name = "bcc")
    private String bcc;

    public String getBcc() {
        return bcc;
    }

    public void setBcc(String bcc) {
        this.bcc = bcc;
    }

    @Column(name = "subject")
    private String subject;

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    @Column(name = "body")
    private String body;

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    @Column(name = "header")
    private String header;

    public String getHeader() {
        return header;
    }

    public void setHeader(String header) {
        this.header = header;
    }

    @Column(name = "sent_at")
    private Instant sentAt;

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    @Column(name = "received_at")
    private Instant receivedAt;

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
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
