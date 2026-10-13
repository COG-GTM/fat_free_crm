package com.fatfreecrm.service.jobs;

import com.fatfreecrm.config.JobsProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class JobsOwner {

    private final String owner;

    public JobsOwner(JobsProperties properties) {
        this.owner = properties.getOwner();
    }

    @PostConstruct
    void validateOwner() {
        if (!"rails".equals(owner) && !"spring".equals(owner)) {
            throw new IllegalStateException("ffcrm.jobs.owner must be rails or spring");
        }
    }

    public boolean isSpring() {
        return "spring".equals(owner);
    }

    public String value() {
        return owner;
    }

    public void requireSpring() {
        if (!isSpring()) {
            throw new IllegalStateException("jobs owner is rails");
        }
    }
}
