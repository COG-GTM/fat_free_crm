package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;

/**
 * acts-as-taggable-on {@code Tag}. {@code taggings_count} is a Rails counter cache.
 */
@Entity
@Table(name = "tags")
public class Tag extends BaseEntity {

    @Column(name = "name")
    private String name;

    @Column(name = "taggings_count")
    private Integer taggingsCount;

    @OneToMany(mappedBy = "tag")
    private Set<Tagging> taggings = new HashSet<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getTaggingsCount() {
        return taggingsCount;
    }

    public void setTaggingsCount(Integer taggingsCount) {
        this.taggingsCount = taggingsCount;
    }

    public Set<Tagging> getTaggings() {
        return Set.copyOf(taggings);
    }
}
