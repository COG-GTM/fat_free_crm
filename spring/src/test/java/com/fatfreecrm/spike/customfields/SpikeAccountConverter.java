package com.fatfreecrm.spike.customfields;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Spike entity (declared in spike/customfields/spike-orm.xml, no @Entity so
 * Spring Boot's entity scan in com.fatfreecrm.** ignores it).
 * {@code custom_fields} mapped through a Jackson String AttributeConverter;
 * {@code @ColumnTransformer} adds the {@code ::jsonb} cast the String binding needs.
 */
@Table(name = "spike_accounts_converter")
public class SpikeAccountConverter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Convert(converter = CustomFieldsJsonConverter.class)
    @ColumnTransformer(write = "?::jsonb")
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
