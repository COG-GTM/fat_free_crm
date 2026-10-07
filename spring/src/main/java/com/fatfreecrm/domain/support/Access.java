package com.fatfreecrm.domain.support;

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

    public static Access fromRailsValue(String value) {
        for (Access access : values()) {
            if (access.railsValue.equals(value)) {
                return access;
            }
        }
        throw new IllegalArgumentException("Unknown access value: " + value);
    }
}
