package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.SQLRestriction;

/**
 * Rails {@code ContactOpportunity} join row. Rails soft-deletes join rows too, so the live association is the single
 * row with {@code deleted_at IS NULL}.
 */
@Entity
@Table(name = "contact_opportunities")
@SQLRestriction("deleted_at IS NULL")
public class ContactOpportunity extends TimestampedEntity implements SoftDeletable {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", columnDefinition = "int4")
    private Contact contact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opportunity_id", columnDefinition = "int4")
    private Opportunity opportunity;

    @Column(name = "role", length = 32)
    private String role;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public Contact getContact() {
        return contact;
    }

    public void setContact(Contact contact) {
        this.contact = contact;
    }

    public Opportunity getOpportunity() {
        return opportunity;
    }

    public void setOpportunity(Opportunity opportunity) {
        this.opportunity = opportunity;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    @Override
    public Instant getDeletedAt() {
        return deletedAt;
    }

    @Override
    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
