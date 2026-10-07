package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
    void emptyAndBlankOnlyInputsNormalizeToNoIds() {
        assertThat(PermissionService.normalizeIds(List.of())).isEmpty();
        assertThat(PermissionService.normalizeIds(Arrays.asList(null, "", "   ", "\t\n"))).isEmpty();
        assertThat(PermissionService.normalizeIds(List.of(List.of(), List.of(List.of())))).isEmpty();
    }

    @Test
    void flattensArbitrarilyNestedCollectionsLikeRubyFlatten() {
        List<Object> nested = List.of(List.of("1", List.of(List.of("2"))), 3, List.of(List.of(List.of(4L))));
        assertThat(PermissionService.normalizeIds(nested)).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void convertsStringsLikeRubyToI() {
        List<Object> values = Arrays.asList(" 12", "+5", "-3", "13.9", "1e3", "0x1A", "2_000", "7abc", "\n8", " 9 ");
        assertThat(PermissionService.normalizeIds(values))
            .containsExactly(12L, 5L, -3L, 13L, 1L, 0L, 2000L, 7L, 8L, 9L);
        assertThat(PermissionService.normalizeIds(List.of("abc"))).containsExactly(0L);
    }

    @Test
    void acceptsAnyNumberTypeAndDropsDuplicatesAfterConversion() {
        List<Object> values = Arrays.asList(3, 3L, (short) 3, "3", "03", " 3", new BigDecimal("3.7"), 4);
        assertThat(PermissionService.normalizeIds(values)).containsExactly(3L, 4L);
    }

    @Test
    void keepsFirstSeenOrder() {
        assertThat(PermissionService.normalizeIds(List.of("9", "1", "5", "1", "9"))).containsExactly(9L, 1L, 5L);
    }
}
