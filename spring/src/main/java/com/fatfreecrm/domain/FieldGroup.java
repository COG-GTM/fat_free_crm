package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;

/**
 * Rails {@code FieldGroup}: a titled section of fields on an entity form, optionally shown only for a tag.
 */
@Entity
@Table(name = "field_groups")
public class FieldGroup extends TimestampedEntity {

    @Column(name = "name", length = 64)
    private String name;

    @Column(name = "label", length = 128)
    private String label;

    @Column(name = "\"position\"")
    private Integer position;

    @Column(name = "hint")
    private String hint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", columnDefinition = "int4")
    private Tag tag;

    @Column(name = "klass_name", length = 32)
    private String klassName;

    @OneToMany(mappedBy = "fieldGroup")
    @OrderBy("position")
    private Set<Field> fields = new HashSet<>();

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

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }

    public Tag getTag() {
        return tag;
    }

    public void setTag(Tag tag) {
        this.tag = tag;
    }

    public String getKlassName() {
        return klassName;
    }

    public void setKlassName(String klassName) {
        this.klassName = klassName;
    }

    public Set<Field> getFields() {
        return Set.copyOf(fields);
    }
}
