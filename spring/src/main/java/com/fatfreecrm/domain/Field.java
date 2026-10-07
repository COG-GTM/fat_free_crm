package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Rails {@code Field} (STI: {@code CoreField}, {@code CustomField}, {@code CustomFieldDatePair}, ...). The Rails
 * subclass name is kept in {@code type} as a plain column rather than a JPA discriminator; {@code collection} and
 * {@code settings} are Rails YAML blobs kept opaque. Custom-field value storage is decided separately (ADR 0003).
 */
@Entity
@Table(name = "fields")
public class Field extends TimestampedEntity {

    @Column(name = "type")
    private String type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "field_group_id", columnDefinition = "int4")
    private FieldGroup fieldGroup;

    @Column(name = "\"position\"")
    private Integer position;

    @Column(name = "name", length = 64)
    private String name;

    @Column(name = "label", length = 128)
    private String label;

    @Column(name = "hint")
    private String hint;

    @Column(name = "placeholder")
    private String placeholder;

    @Column(name = "\"as\"", length = 32)
    private String inputType;

    @Column(name = "collection")
    private String collection;

    @Column(name = "disabled")
    private Boolean disabled;

    @Column(name = "required")
    private Boolean required;

    @Column(name = "maxlength")
    private Integer maxlength;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pair_id", columnDefinition = "int4")
    private Field pair;

    @Column(name = "settings")
    private String settings;

    @Column(name = "minlength")
    private Integer minlength;

    @Column(name = "pattern")
    private String pattern;

    @Column(name = "autofocus")
    private String autofocus;

    @Column(name = "autocomplete")
    private String autocomplete;

    @Column(name = "list")
    private String listAttribute;

    @Column(name = "multiple")
    private String multiple;

    @Column(name = "title")
    private String title;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public FieldGroup getFieldGroup() {
        return fieldGroup;
    }

    public void setFieldGroup(FieldGroup fieldGroup) {
        this.fieldGroup = fieldGroup;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    public String getInputType() {
        return inputType;
    }

    public void setInputType(String inputType) {
        this.inputType = inputType;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public Boolean getDisabled() {
        return disabled;
    }

    public void setDisabled(Boolean disabled) {
        this.disabled = disabled;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public Integer getMaxlength() {
        return maxlength;
    }

    public void setMaxlength(Integer maxlength) {
        this.maxlength = maxlength;
    }

    public Field getPair() {
        return pair;
    }

    public void setPair(Field pair) {
        this.pair = pair;
    }

    public String getSettings() {
        return settings;
    }

    public void setSettings(String settings) {
        this.settings = settings;
    }

    public Integer getMinlength() {
        return minlength;
    }

    public void setMinlength(Integer minlength) {
        this.minlength = minlength;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getAutofocus() {
        return autofocus;
    }

    public void setAutofocus(String autofocus) {
        this.autofocus = autofocus;
    }

    public String getAutocomplete() {
        return autocomplete;
    }

    public void setAutocomplete(String autocomplete) {
        this.autocomplete = autocomplete;
    }

    public String getListAttribute() {
        return listAttribute;
    }

    public void setListAttribute(String listAttribute) {
        this.listAttribute = listAttribute;
    }

    public String getMultiple() {
        return multiple;
    }

    public void setMultiple(String multiple) {
        this.multiple = multiple;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
