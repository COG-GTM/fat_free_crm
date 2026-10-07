package com.fatfreecrm.domain.support;

import java.util.Optional;

public enum Access {
    PUBLIC("Public"),
    PRIVATE("Private"),
    SHARED("Shared");

    private final String railsValue;

    Access(String railsValue) {
        this.railsValue = railsValue;
    }

    public String railsValue() {
        return railsValue;
    }

    public static Optional<Access> fromRailsValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (Access access : values()) {
            if (access.railsValue.equals(value)) {
                return Optional.of(access);
            }
        }
        return Optional.empty();
    }
}
