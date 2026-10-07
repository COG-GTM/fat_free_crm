package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.annotations.SQLRestriction;

/**
 * Rails {@code Opportunity}: a potential deal, linked to one account and many contacts through join rows.
 */
@Entity
@Table(name = "opportunities")
@SQLRestriction("deleted_at IS NULL")
public class Opportunity extends CrmEntity {

    public static final String RAILS_TYPE = "Opportunity";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", columnDefinition = "int4")
    private Campaign campaign;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "source", length = 32)
    private String source;

    @Column(name = "stage", length = 32)
    private String stage;

    @Column(name = "probability")
    private Integer probability;

    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "discount", precision = 12, scale = 2)
    private BigDecimal discount;

    @Column(name = "closes_on")
    private LocalDate closesOn;

    @OneToMany(mappedBy = "opportunity")
    private Set<AccountOpportunity> accountOpportunities = new HashSet<>();

    @OneToMany(mappedBy = "opportunity")
    private Set<ContactOpportunity> contactOpportunities = new HashSet<>();

    @Override
    public String railsType() {
        return RAILS_TYPE;
    }

    public Campaign getCampaign() {
        return campaign;
    }

    public void setCampaign(Campaign campaign) {
        this.campaign = campaign;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public Integer getProbability() {
        return probability;
    }

    public void setProbability(Integer probability) {
        this.probability = probability;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public void setDiscount(BigDecimal discount) {
        this.discount = discount;
    }

    public LocalDate getClosesOn() {
        return closesOn;
    }

    public void setClosesOn(LocalDate closesOn) {
        this.closesOn = closesOn;
    }

    public Set<AccountOpportunity> getAccountOpportunities() {
        return Set.copyOf(accountOpportunities);
    }

    public Set<ContactOpportunity> getContactOpportunities() {
        return Set.copyOf(contactOpportunities);
    }
}
