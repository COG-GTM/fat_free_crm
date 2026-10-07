package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * Rails {@code Lead}: an unqualified prospect, convertible into a {@link Contact}.
 */
@Entity
@Table(name = "leads")
@SQLRestriction("deleted_at IS NULL")
public class Lead extends CrmEntity {

    public static final String RAILS_TYPE = "Lead";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", columnDefinition = "int4")
    private Campaign campaign;

    @Column(name = "first_name", nullable = false, length = 64)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 64)
    private String lastName;

    @Column(name = "title", length = 64)
    private String title;

    @Column(name = "company", length = 64)
    private String company;

    @Column(name = "source", length = 32)
    private String source;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "referred_by", length = 64)
    private String referredBy;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "alt_email", length = 254)
    private String altEmail;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "mobile", length = 32)
    private String mobile;

    @Column(name = "blog", length = 128)
    private String blog;

    @Column(name = "linkedin", length = 128)
    private String linkedin;

    @Column(name = "facebook", length = 128)
    private String facebook;

    @Column(name = "twitter", length = 128)
    private String twitter;

    @Column(name = "rating", nullable = false)
    private int rating;

    @Column(name = "do_not_call", nullable = false)
    private boolean doNotCall;

    @Column(name = "zoom", length = 128)
    private String zoom;

    @Column(name = "teams", length = 128)
    private String teams;

    @Column(name = "signal", length = 128)
    private String signal;

    @Column(name = "instagram", length = 128)
    private String instagram;

    @Column(name = "mastodon", length = 128)
    private String mastodon;

    @Column(name = "bluesky", length = 128)
    private String bluesky;

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

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReferredBy() {
        return referredBy;
    }

    public void setReferredBy(String referredBy) {
        this.referredBy = referredBy;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAltEmail() {
        return altEmail;
    }

    public void setAltEmail(String altEmail) {
        this.altEmail = altEmail;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public String getBlog() {
        return blog;
    }

    public void setBlog(String blog) {
        this.blog = blog;
    }

    public String getLinkedin() {
        return linkedin;
    }

    public void setLinkedin(String linkedin) {
        this.linkedin = linkedin;
    }

    public String getFacebook() {
        return facebook;
    }

    public void setFacebook(String facebook) {
        this.facebook = facebook;
    }

    public String getTwitter() {
        return twitter;
    }

    public void setTwitter(String twitter) {
        this.twitter = twitter;
    }

    public int getRating() {
        return rating;
    }

    public void setRating(int rating) {
        this.rating = rating;
    }

    public boolean isDoNotCall() {
        return doNotCall;
    }

    public void setDoNotCall(boolean doNotCall) {
        this.doNotCall = doNotCall;
    }

    public String getZoom() {
        return zoom;
    }

    public void setZoom(String zoom) {
        this.zoom = zoom;
    }

    public String getTeams() {
        return teams;
    }

    public void setTeams(String teams) {
        this.teams = teams;
    }

    public String getSignal() {
        return signal;
    }

    public void setSignal(String signal) {
        this.signal = signal;
    }

    public String getInstagram() {
        return instagram;
    }

    public void setInstagram(String instagram) {
        this.instagram = instagram;
    }

    public String getMastodon() {
        return mastodon;
    }

    public void setMastodon(String mastodon) {
        this.mastodon = mastodon;
    }

    public String getBluesky() {
        return bluesky;
    }

    public void setBluesky(String bluesky) {
        this.bluesky = bluesky;
    }
}
