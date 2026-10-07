package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Rails {@code ResearchTool}: an external lookup link template shown on entity pages.
 */
@Entity
@Table(name = "research_tools")
public class ResearchTool extends TimestampedEntity {

    @Column(name = "name")
    private String name;

    @Column(name = "url_template")
    private String urlTemplate;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrlTemplate() {
        return urlTemplate;
    }

    public void setUrlTemplate(String urlTemplate) {
        this.urlTemplate = urlTemplate;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
