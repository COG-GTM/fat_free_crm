package com.fatfreecrm.domain;

import com.fatfreecrm.domain.support.CrmEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "accounts")
@DynamicUpdate
@SQLRestriction("deleted_at IS NULL")
public class Account extends CrmEntity {

    @Column(name = "name", length = 64, nullable = false)
    private String name = "";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Column(name = "website", length = 64)
    private String website;

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    @Column(name = "toll_free_phone", length = 32)
    private String tollFreePhone;

    public String getTollFreePhone() {
        return tollFreePhone;
    }

    public void setTollFreePhone(String tollFreePhone) {
        this.tollFreePhone = tollFreePhone;
    }

    @Column(name = "phone", length = 32)
    private String phone;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    @Column(name = "fax", length = 32)
    private String fax;

    public String getFax() {
        return fax;
    }

    public void setFax(String fax) {
        this.fax = fax;
    }

    @Column(name = "email", length = 254)
    private String email;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @Column(name = "background_info")
    private String backgroundInfo;

    public String getBackgroundInfo() {
        return backgroundInfo;
    }

    public void setBackgroundInfo(String backgroundInfo) {
        this.backgroundInfo = backgroundInfo;
    }

    @Column(name = "rating", nullable = false)
    private Integer rating = 0;

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    @Column(name = "category", length = 32)
    private String category;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    @Column(name = "contacts_count")
    private Integer contactsCount = 0;

    public Integer getContactsCount() {
        return contactsCount;
    }

    public void setContactsCount(Integer contactsCount) {
        this.contactsCount = contactsCount;
    }

    @Column(name = "opportunities_count")
    private Integer opportunitiesCount = 0;

    public Integer getOpportunitiesCount() {
        return opportunitiesCount;
    }

    public void setOpportunitiesCount(Integer opportunitiesCount) {
        this.opportunitiesCount = opportunitiesCount;
    }

    @Column(name = "wikidata_id")
    private String wikidataId;

    public String getWikidataId() {
        return wikidataId;
    }

    public void setWikidataId(String wikidataId) {
        this.wikidataId = wikidataId;
    }

    @Column(name = "latitude", precision = 10, scale = 6)
    private BigDecimal latitude;

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
    }

    @Column(name = "longitude", precision = 10, scale = 6)
    private BigDecimal longitude;

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
    }

    @Column(name = "blog")
    private String blog;

    public String getBlog() {
        return blog;
    }

    public void setBlog(String blog) {
        this.blog = blog;
    }

    @Column(name = "linkedin")
    private String linkedin;

    public String getLinkedin() {
        return linkedin;
    }

    public void setLinkedin(String linkedin) {
        this.linkedin = linkedin;
    }

    @Column(name = "facebook")
    private String facebook;

    public String getFacebook() {
        return facebook;
    }

    public void setFacebook(String facebook) {
        this.facebook = facebook;
    }

    @Column(name = "twitter")
    private String twitter;

    public String getTwitter() {
        return twitter;
    }

    public void setTwitter(String twitter) {
        this.twitter = twitter;
    }

    @Column(name = "bluesky")
    private String bluesky;

    public String getBluesky() {
        return bluesky;
    }

    public void setBluesky(String bluesky) {
        this.bluesky = bluesky;
    }

    @Column(name = "instagram")
    private String instagram;

    public String getInstagram() {
        return instagram;
    }

    public void setInstagram(String instagram) {
        this.instagram = instagram;
    }

    @Column(name = "mastodon")
    private String mastodon;

    public String getMastodon() {
        return mastodon;
    }

    public void setMastodon(String mastodon) {
        this.mastodon = mastodon;
    }

}
