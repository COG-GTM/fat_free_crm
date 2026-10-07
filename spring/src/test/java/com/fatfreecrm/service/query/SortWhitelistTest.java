package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Sort key resolution: field keys, Rails preference form, anything else falls back to the default. */
class SortWhitelistTest {

    private final SortWhitelist whitelist = SortWhitelist.of(
        com.fatfreecrm.domain.Account.class,
        "created_at DESC", "name ASC", "rating DESC", "created_at DESC", "updated_at DESC");

    @Test
    void fieldKeyResolves() {
        assertThat(whitelist.resolve("name")).isNotNull();
        assertThat(whitelist.resolve("rating")).isNotNull();
    }

    @Test
    void railsPreferenceFormResolves() {
        assertThat(whitelist.resolve("accounts.name ASC")).isNotNull();
        assertThat(whitelist.resolve("accounts.name asc")).isNotNull();
        // Explicit direction must match the whitelist entry's direction.
        assertThat(whitelist.resolve("name DESC")).isNull();
        assertThat(whitelist.resolve("name ASC")).isNotNull();
        assertThat(whitelist.resolve("rating DESC")).isNotNull();
        assertThat(whitelist.resolve("rating ASC")).isNull();
    }

    @Test
    void unknownOrInvalidInputResolvesToNull() {
        assertThat(whitelist.resolve("bogus")).isNull();
        assertThat(whitelist.resolve("name; drop table accounts")).isNull();
        assertThat(whitelist.resolve("accounts.rating SIDEWAYS")).isNull();
        assertThat(whitelist.resolve(null)).isNull();
        assertThat(whitelist.resolve("  ")).isNull();
    }
}
