package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.annotations.SQLRestriction;

/**
 * Maps the Rails {@code contacts} table (db/schema.rb) column-for-column, excluding dynamic
 * {@code cf_*} custom-field columns. {@code leadId} / {@code reportsTo} are plain FK ids.
 * Soft-deleted rows are hidden by the {@code deleted_at IS NULL} restriction.
 */
@Entity
@Table(name = "contacts")
@SQLRestriction("deleted_at IS NULL")
public class Contact extends CrmEntity {

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "reports_to")
    private Long reportsTo;

    @Column(name = "first_name", length = 64, nullable = false)
    private String firstName;

    @Column(name = "last_name", length = 64, nullable = false)
    private String lastName;

    @Column(name = "title", length = 64)
    private String title;

    @Column(name = "department", length = 64)
    private String department;

    @Column(name = "source", length = 32)
    private String source;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "alt_email", length = 254)
    private String altEmail;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "mobile", length = 32)
    private String mobile;

    @Column(name = "fax", length = 32)
    private String fax;

    @Column(name = "blog", length = 128)
    private String blog;

    @Column(name = "linkedin", length = 128)
    private String linkedin;

    @Column(name = "facebook", length = 128)
    private String facebook;

    @Column(name = "twitter", length = 128)
    private String twitter;

    @Column(name = "born_on")
    private LocalDate bornOn;

    @Column(name = "do_not_call", nullable = false)
    private Boolean doNotCall;

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

    public Long getLeadId() {
        return leadId;
    }

    public void setLeadId(Long leadId) {
        this.leadId = leadId;
    }

    public Long getReportsTo() {
        return reportsTo;
    }

    public void setReportsTo(Long reportsTo) {
        this.reportsTo = reportsTo;
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

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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

    public String getFax() {
        return fax;
    }

    public void setFax(String fax) {
        this.fax = fax;
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

    public LocalDate getBornOn() {
        return bornOn;
    }

    public void setBornOn(LocalDate bornOn) {
        this.bornOn = bornOn;
    }

    public Boolean getDoNotCall() {
        return doNotCall;
    }

    public void setDoNotCall(Boolean doNotCall) {
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
