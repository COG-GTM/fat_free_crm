package com.fatfreecrm.domain;

import com.fatfreecrm.domain.support.RailsYaml;
import com.fatfreecrm.domain.support.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "fields")
@DynamicUpdate
public class Field extends TimestampedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "field_group_id", columnDefinition = "int4")
    private FieldGroup fieldGroup;

    public FieldGroup getFieldGroup() {
        return fieldGroup;
    }

    public void setFieldGroup(FieldGroup fieldGroup) {
        this.fieldGroup = fieldGroup;
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pair_id", columnDefinition = "int4")
    private Field pair;

    public Field getPair() {
        return pair;
    }

    public void setPair(Field pair) {
        this.pair = pair;
    }

    @Column(name = "type")
    private String type;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Column(name = "\"position\"")
    private Integer position;

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    @Column(name = "name", length = 64)
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Column(name = "label", length = 128)
    private String label;

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    @Column(name = "hint")
    private String hint;

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }

    @Column(name = "placeholder")
    private String placeholder;

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    @Column(name = "\"as\"", length = 32)
    private String asValue;

    public String getAsValue() {
        return asValue;
    }

    public void setAsValue(String asValue) {
        this.asValue = asValue;
    }

    @Column(name = "collection")
    private String collection;

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    @Column(name = "disabled")
    private Boolean disabled;

    public Boolean getDisabled() {
        return disabled;
    }

    public void setDisabled(Boolean disabled) {
        this.disabled = disabled;
    }

    @Column(name = "required")
    private Boolean required;

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    @Column(name = "maxlength")
    private Integer maxlength;

    public Integer getMaxlength() {
        return maxlength;
    }

    public void setMaxlength(Integer maxlength) {
        this.maxlength = maxlength;
    }

    @Column(name = "settings")
    private String settings;

    public String getSettings() {
        return settings;
    }

    public void setSettings(String settings) {
        this.settings = settings;
    }

    @Column(name = "minlength")
    private Integer minlength = 0;

    public Integer getMinlength() {
        return minlength;
    }

    public void setMinlength(Integer minlength) {
        this.minlength = minlength;
    }

    @Column(name = "pattern")
    private String pattern;

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    @Column(name = "autofocus")
    private String autofocus;

    public String getAutofocus() {
        return autofocus;
    }

    public void setAutofocus(String autofocus) {
        this.autofocus = autofocus;
    }

    @Column(name = "autocomplete")
    private String autocomplete;

    public String getAutocomplete() {
        return autocomplete;
    }

    public void setAutocomplete(String autocomplete) {
        this.autocomplete = autocomplete;
    }

    @Column(name = "list")
    private String list;

    public String getList() {
        return list;
    }

    public void setList(String list) {
        this.list = list;
    }

    @Column(name = "multiple")
    private String multiple;

    public String getMultiple() {
        return multiple;
    }

    public void setMultiple(String multiple) {
        this.multiple = multiple;
    }

    @Column(name = "title")
    private String title;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<String> getCollectionValues() {
        return RailsYaml.readStringList(collection);
    }

    public Map<String, Object> getSettingsMap() {
        return RailsYaml.readStringMap(settings);
    }

}
