package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link PermissionService#normalizeIds} to Ruby's
 * {@code value.flatten.reject(&:blank?).uniq.map(&:to_i)} from {@code FatFreeCRM::Permissions}.
 */
class PermissionServiceIdNormalizationTest {

    @Test
    void emptyAndBlankOnlyInputsProduceNoIds() {
        assertThat(PermissionService.normalizeIds(List.of())).isEmpty();
        assertThat(PermissionService.normalizeIds(Arrays.asList(null, "", "   ", "\t\n"))).isEmpty();
        assertThat(PermissionService.normalizeIds(List.of(List.of(), List.of(List.of())))).isEmpty();
    }

    @Test
    void stringsConvertLikeRubyToI() {
        assertThat(PermissionService.normalizeIds(List.of("+5", "-3", "1_000", "  42abc", "7.9", "0x1f")))
            .containsExactly(5L, -3L, 1000L, 42L, 7L, 0L);
        assertThat(PermissionService.normalizeIds(List.of("abc"))).containsExactly(0L);
    }

    @Test
    void numbersTruncateLikeRubyToI() {
        assertThat(PermissionService.normalizeIds(List.of(3.9d, 4.1f, (short) 5, 6, 7L)))
            .containsExactly(3L, 4L, 5L, 6L, 7L);
    }

    @Test
    void deeplyNestedCollectionsAreFlattenedInOrder() {
        List<Object> nested = List.of("1", List.of("2", Set.of("3"), List.of(List.of(4))), 5);
        assertThat(PermissionService.normalizeIds(nested)).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void equalIdsSpelledDifferentlyCollapseToOneRow() {
        // Ruby runs uniq before to_i and would keep both "3" and 3; Java de-duplicates after
        // conversion so a single permission row is written. Visibility is identical either way.
        assertThat(PermissionService.normalizeIds(List.of("3", 3, " 3", "3abc", 3L))).containsExactly(3L);
    }
}
