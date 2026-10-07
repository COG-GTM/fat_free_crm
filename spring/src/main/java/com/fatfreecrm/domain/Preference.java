package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Rails {@code Preference}: per-user key/value. {@code value} is Base64-encoded JSON written by Rails
 * ({@code Base64.encode64(value.to_json)}) and is kept opaque here so Rails can keep reading it.
 */
@Entity
@Table(name = "preferences")
public class Preference extends TimestampedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "int4")
    private User user;

    @Column(name = "name", nullable = false, length = 32)
    private String name;

    @Column(name = "value")
    private String value;

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
