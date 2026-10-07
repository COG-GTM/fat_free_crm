package com.fatfreecrm.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.SQLRestriction;

/**
 * Rails {@code Address} (Business/Billing/Shipping) attached to an account, contact or lead.
 */
@Entity
@Table(name = "addresses")
@SQLRestriction("deleted_at IS NULL")
public class Address extends TimestampedEntity implements SoftDeletable {

    @Column(name = "street1")
    private String street1;

    @Column(name = "street2")
    private String street2;

    @Column(name = "city", length = 64)
    private String city;

    @Column(name = "state", length = 64)
    private String state;

    @Column(name = "zipcode", length = 16)
    private String zipcode;

    @Column(name = "country", length = 64)
    private String country;

    @Column(name = "full_address")
    private String fullAddress;

    @Column(name = "address_type", length = 16)
    private String addressType;

    @Embedded
    @AttributeOverride(name = "type", column = @Column(name = "addressable_type"))
    @AttributeOverride(name = "id", column = @Column(name = "addressable_id", columnDefinition = "int4"))
    private PolymorphicRef addressable;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public String getStreet1() {
        return street1;
    }

    public void setStreet1(String street1) {
        this.street1 = street1;
    }

    public String getStreet2() {
        return street2;
    }

    public void setStreet2(String street2) {
        this.street2 = street2;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getZipcode() {
        return zipcode;
    }

    public void setZipcode(String zipcode) {
        this.zipcode = zipcode;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getFullAddress() {
        return fullAddress;
    }

    public void setFullAddress(String fullAddress) {
        this.fullAddress = fullAddress;
    }

    public String getAddressType() {
        return addressType;
    }

    public void setAddressType(String addressType) {
        this.addressType = addressType;
    }

    public PolymorphicRef getAddressable() {
        return addressable;
    }

    public void setAddressable(PolymorphicRef addressable) {
        this.addressable = addressable;
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
