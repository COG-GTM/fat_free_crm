package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Rails {@code Setting}: application-wide key/value overriding {@code config/settings.default.yml}. {@code value}
 * is a YAML document produced by {@code serialize :value} and is kept opaque here.
 */
@Entity
@Table(name = "settings")
public class Setting extends TimestampedEntity {

    @Column(name = "name", nullable = false, length = 32)
    private String name;

    @Column(name = "value")
    private String value;

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
