package com.fatfreecrm.domain;

import com.fatfreecrm.domain.support.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "tags")
@DynamicUpdate
public class Tag extends BaseEntity {

    @Column(name = "name")
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Column(name = "taggings_count")
    private Integer taggingsCount = 0;

    public Integer getTaggingsCount() {
        return taggingsCount;
    }

    public void setTaggingsCount(Integer taggingsCount) {
        this.taggingsCount = taggingsCount;
    }

}
