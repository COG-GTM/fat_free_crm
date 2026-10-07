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
}
