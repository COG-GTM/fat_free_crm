package com.fatfreecrm.domain;

import com.fatfreecrm.domain.support.RailsYaml;
import com.fatfreecrm.domain.support.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "settings")
@DynamicUpdate
public class Setting extends TimestampedEntity {

    @Column(name = "name", length = 32, nullable = false)
    private String name = "";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Column(name = "value")
    private String value;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Object getParsedValue() {
        return RailsYaml.read(value);
    }

}
