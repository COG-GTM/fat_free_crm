package com.fatfreecrm.spike.customfields;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Spike entity (declared in spike/customfields/spike-orm.xml, no @Entity so
 * Spring Boot's entity scan in com.fatfreecrm.** ignores it).
 * {@code custom_fields} mapped directly as JSON via {@code @JdbcTypeCode(SqlTypes.JSON)}.
 */
@Table(name = "spike_accounts_json")
public class SpikeAccountJson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_fields", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> customFields = new LinkedHashMap<>();

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getCustomFields() {
        return customFields;
    }

    public void setCustomFields(Map<String, Object> customFields) {
        this.customFields = customFields;
    }
}
