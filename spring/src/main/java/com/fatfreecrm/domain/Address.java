package com.fatfreecrm.domain;

import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.domain.support.RailsModelTypeConverter;
import com.fatfreecrm.domain.support.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "addresses")
@DynamicUpdate
@SQLRestriction("deleted_at IS NULL")
public class Address extends TimestampedEntity {

    @Convert(converter = RailsModelTypeConverter.class)
    @Column(name = "addressable_type")
    private RailsModelType addressableType;

    @Column(name = "addressable_id")
    private Integer addressableId;

    public RailsModelType getAddressableType() {
        return addressableType;
    }

    public void setAddressableType(RailsModelType addressableType) {
        this.addressableType = addressableType;
    }

    public Integer getAddressableId() {
        return addressableId;
    }

    public void setAddressableId(Integer addressableId) {
        this.addressableId = addressableId;
    }

    @Column(name = "street1")
    private String street1;

    public String getStreet1() {
        return street1;
    }

    public void setStreet1(String street1) {
        this.street1 = street1;
    }

    @Column(name = "street2")
    private String street2;

    public String getStreet2() {
        return street2;
    }

    public void setStreet2(String street2) {
        this.street2 = street2;
    }

    @Column(name = "city", length = 64)
    private String city;

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    @Column(name = "state", length = 64)
    private String state;

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    @Column(name = "zipcode", length = 16)
    private String zipcode;

    public String getZipcode() {
        return zipcode;
    }

    public void setZipcode(String zipcode) {
        this.zipcode = zipcode;
    }

    @Column(name = "country", length = 64)
    private String country;

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    @Column(name = "full_address")
    private String fullAddress;

    public String getFullAddress() {
        return fullAddress;
    }

    public void setFullAddress(String fullAddress) {
        this.fullAddress = fullAddress;
    }

    @Column(name = "address_type", length = 16)
    private String addressType;

    public String getAddressType() {
        return addressType;
    }

    public void setAddressType(String addressType) {
        this.addressType = addressType;
    }

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

}
