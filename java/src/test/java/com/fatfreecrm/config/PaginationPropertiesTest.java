package com.fatfreecrm.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link PaginationProperties} must reproduce the Rails {@code per_page} rules:
 * {@code Account.per_page == 20} (default) and
 * {@code EntitiesController#per_page_param == [1, [per_page, 200].min].max}.
 */
class PaginationPropertiesTest {

    private final PaginationProperties defaults = new PaginationProperties(0, 0);

    @Test
    void zeroValuesFallBackToRailsDefaults() {
        assertThat(defaults.defaultPageSize()).isEqualTo(20);
        assertThat(defaults.maxPageSize()).isEqualTo(200);
        assertThat(PaginationProperties.DEFAULT_PAGE_SIZE).isEqualTo(20);
        assertThat(PaginationProperties.MAX_PAGE_SIZE).isEqualTo(200);
    }

    @Test
    void explicitValuesAreKept() {
        PaginationProperties custom = new PaginationProperties(10, 50);

        assertThat(custom.defaultPageSize()).isEqualTo(10);
        assertThat(custom.maxPageSize()).isEqualTo(50);
    }

    @Test
    void nullRequestUsesTheDefaultPageSize() {
        assertThat(defaults.clampPageSize(null)).isEqualTo(20);
        assertThat(new PaginationProperties(10, 50).clampPageSize(null)).isEqualTo(10);
    }

    @Test
    void requestsBelowOneClampToOne() {
        assertThat(defaults.clampPageSize(0)).isEqualTo(1);
        assertThat(defaults.clampPageSize(-5)).isEqualTo(1);
        assertThat(defaults.clampPageSize(Integer.MIN_VALUE)).isEqualTo(1);
    }

    @Test
    void requestsAboveMaxClampToMax() {
        assertThat(defaults.clampPageSize(201)).isEqualTo(200);
        assertThat(defaults.clampPageSize(Integer.MAX_VALUE)).isEqualTo(200);
        assertThat(new PaginationProperties(10, 50).clampPageSize(51)).isEqualTo(50);
    }

    @Test
    void requestsInsideTheRangeAreReturnedUnchanged() {
        assertThat(defaults.clampPageSize(1)).isEqualTo(1);
        assertThat(defaults.clampPageSize(20)).isEqualTo(20);
        assertThat(defaults.clampPageSize(200)).isEqualTo(200);
        assertThat(defaults.clampPageSize(137)).isEqualTo(137);
    }
}
