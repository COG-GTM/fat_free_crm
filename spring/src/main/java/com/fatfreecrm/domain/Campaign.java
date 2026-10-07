package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.annotations.SQLRestriction;

/**
 * Rails {@code Campaign}: a marketing campaign that leads and opportunities originate from.
 */
@Entity
@Table(name = "campaigns")
@SQLRestriction("deleted_at IS NULL")
public class Campaign extends CrmEntity {

    public static final String RAILS_TYPE = "Campaign";

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "status", length = 64)
    private String status;

    @Column(name = "budget", precision = 12, scale = 2)
    private BigDecimal budget;

    @Column(name = "target_leads")
    private Integer targetLeads;

    @Column(name = "target_conversion")
    private Double targetConversion;

    @Column(name = "target_revenue", precision = 12, scale = 2)
    private BigDecimal targetRevenue;

    @Column(name = "leads_count")
    private Integer leadsCount;

    @Column(name = "opportunities_count")
    private Integer opportunitiesCount;

    @Column(name = "revenue", precision = 12, scale = 2)
    private BigDecimal revenue;

    @Column(name = "starts_on")
    private LocalDate startsOn;

    @Column(name = "ends_on")
    private LocalDate endsOn;

    @Column(name = "objectives")
    private String objectives;

    @OneToMany(mappedBy = "campaign")
    private Set<Lead> leads = new HashSet<>();

    @OneToMany(mappedBy = "campaign")
    private Set<Opportunity> opportunities = new HashSet<>();

    @Override
    public String railsType() {
        return RAILS_TYPE;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getBudget() {
        return budget;
    }

    public void setBudget(BigDecimal budget) {
        this.budget = budget;
    }

    public Integer getTargetLeads() {
        return targetLeads;
    }

    public void setTargetLeads(Integer targetLeads) {
        this.targetLeads = targetLeads;
    }

    public Double getTargetConversion() {
        return targetConversion;
    }

    public void setTargetConversion(Double targetConversion) {
        this.targetConversion = targetConversion;
    }

    public BigDecimal getTargetRevenue() {
        return targetRevenue;
    }

    public void setTargetRevenue(BigDecimal targetRevenue) {
        this.targetRevenue = targetRevenue;
    }

    public Integer getLeadsCount() {
        return leadsCount;
    }

    public void setLeadsCount(Integer leadsCount) {
        this.leadsCount = leadsCount;
    }

    public Integer getOpportunitiesCount() {
        return opportunitiesCount;
    }

    public void setOpportunitiesCount(Integer opportunitiesCount) {
        this.opportunitiesCount = opportunitiesCount;
    }

    public BigDecimal getRevenue() {
        return revenue;
    }

    public void setRevenue(BigDecimal revenue) {
        this.revenue = revenue;
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public void setStartsOn(LocalDate startsOn) {
        this.startsOn = startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public void setEndsOn(LocalDate endsOn) {
        this.endsOn = endsOn;
    }

    public String getObjectives() {
        return objectives;
    }

    public void setObjectives(String objectives) {
        this.objectives = objectives;
    }

    public Set<Lead> getLeads() {
        return Set.copyOf(leads);
    }

    public Set<Opportunity> getOpportunities() {
        return Set.copyOf(opportunities);
    }
}
