package com.fatfreecrm.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.SQLRestriction;

/**
 * Rails {@code Email} dropped into the CRM by the IMAP dropbox and attached to a mediator entity.
 */
@Entity
@Table(name = "emails")
@SQLRestriction("deleted_at IS NULL")
public class Email extends TimestampedEntity implements SoftDeletable {

    @Column(name = "imap_message_id", nullable = false)
    private String imapMessageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    @Embedded
    @AttributeOverride(name = "type", column = @Column(name = "mediator_type"))
    @AttributeOverride(name = "id", column = @Column(name = "mediator_id", columnDefinition = "int4"))
    private PolymorphicRef mediator;

    @Column(name = "sent_from", nullable = false)
    private String sentFrom;

    @Column(name = "sent_to", nullable = false)
    private String sentTo;

    @Column(name = "cc")
    private String cc;

    @Column(name = "bcc")
    private String bcc;

    @Column(name = "subject")
    private String subject;

    @Column(name = "body")
    private String body;

    @Column(name = "header")
    private String header;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    public String getImapMessageId() {
        return imapMessageId;
    }

    public void setImapMessageId(String imapMessageId) {
        this.imapMessageId = imapMessageId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public PolymorphicRef getMediator() {
        return mediator;
    }

    public void setMediator(PolymorphicRef mediator) {
        this.mediator = mediator;
    }

    public String getSentFrom() {
        return sentFrom;
    }

    public void setSentFrom(String sentFrom) {
        this.sentFrom = sentFrom;
    }

    public String getSentTo() {
        return sentTo;
    }

    public void setSentTo(String sentTo) {
        this.sentTo = sentTo;
    }

    public String getCc() {
        return cc;
    }

    public void setCc(String cc) {
        this.cc = cc;
    }

    public String getBcc() {
        return bcc;
    }

    public void setBcc(String bcc) {
        this.bcc = bcc;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getHeader() {
        return header;
    }

    public void setHeader(String header) {
        this.header = header;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    @Override
    public Instant getDeletedAt() {
        return deletedAt;
    }

    @Override
    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
