package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PermissionServiceTest {

    @Test
    void normalizesIdsLikeRubyFlattenRejectBlankUniqToI() {
        List<Object> nested = Arrays.asList("3", List.of("4", " "), "", null, 3, "3", "7abc", "x", 5L);
        assertThat(PermissionService.normalizeIds(nested)).containsExactly(3L, 4L, 7L, 0L, 5L);
    }

    @Test
    void convertsStringsLikeRubyToI() {
        List<Object> values = Arrays.asList("1_000", "+5", " -3x", "abc", "0x1A", "12.9", 3.9d, 2.1f, (short) 9);
        assertThat(PermissionService.normalizeIds(values)).containsExactly(1000L, 5L, -3L, 0L, 12L, 3L, 2L, 9L);
    }

    @Test
    void deDuplicatesAfterConversionWhereRubyWouldWriteTwoRows() {
        assertThat(PermissionService.normalizeIds(Arrays.asList("3", 3, " 3", 3L, "3x"))).containsExactly(3L);
        assertThat(PermissionService.normalizeIds(Arrays.asList("abc", "xyz", 0))).containsExactly(0L);
    }

    @Test
    void emptyAndBlankOnlyInputsProduceNoIds() {
        assertThat(PermissionService.normalizeIds(List.of())).isEmpty();
        assertThat(PermissionService.normalizeIds(Arrays.asList("", "  ", null, List.of(), List.of(List.of(" ")))))
            .isEmpty();
    }
}
