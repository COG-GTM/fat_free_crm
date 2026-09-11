package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.SQLRestriction;

/**
 * Maps the Rails {@code accounts} table (db/schema.rb) column-for-column, excluding dynamic
 * {@code cf_*} custom-field columns (deferred to the JSONB strategy, see
 * docs/migration/custom-fields-migration.md). Soft-deleted rows are hidden by the
 * {@code deleted_at IS NULL} restriction, mirroring the Rails paranoia default scope.
 */
@Entity
@Table(name = "accounts")
@SQLRestriction("deleted_at IS NULL")
public class Account extends CrmEntity {

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "website", length = 64)
    private String website;

    @Column(name = "toll_free_phone", length = 32)
    private String tollFreePhone;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "fax", length = 32)
    private String fax;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "category", length = 32)
    private String category;

    @Column(name = "contacts_count")
    private Integer contactsCount;

    @Column(name = "opportunities_count")
    private Integer opportunitiesCount;

    @Column(name = "wikidata_id")
    private String wikidataId;

    @Column(name = "latitude", precision = 10, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 6)
    private BigDecimal longitude;

    @Column(name = "blog")
    private String blog;

    @Column(name = "linkedin")
    private String linkedin;

    @Column(name = "facebook")
    private String facebook;

    @Column(name = "twitter")
    private String twitter;

    @Column(name = "bluesky")
    private String bluesky;

    @Column(name = "instagram")
    private String instagram;

    @Column(name = "mastodon")
    private String mastodon;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getTollFreePhone() {
        return tollFreePhone;
    }

    public void setTollFreePhone(String tollFreePhone) {
        this.tollFreePhone = tollFreePhone;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getFax() {
        return fax;
    }

    public void setFax(String fax) {
        this.fax = fax;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getContactsCount() {
        return contactsCount;
    }

    public void setContactsCount(Integer contactsCount) {
        this.contactsCount = contactsCount;
    }

    public Integer getOpportunitiesCount() {
        return opportunitiesCount;
    }

    public void setOpportunitiesCount(Integer opportunitiesCount) {
        this.opportunitiesCount = opportunitiesCount;
    }

    public String getWikidataId() {
        return wikidataId;
    }

    public void setWikidataId(String wikidataId) {
        this.wikidataId = wikidataId;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
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

    public String getBluesky() {
        return bluesky;
    }

    public void setBluesky(String bluesky) {
        this.bluesky = bluesky;
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
}
