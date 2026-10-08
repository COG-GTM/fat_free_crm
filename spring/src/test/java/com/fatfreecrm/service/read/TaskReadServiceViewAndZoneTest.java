package com.fatfreecrm.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.service.query.InvalidSearchQueryException;
import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Rails {@code TasksController#view} and the session {@code Time.zone} handling, without a database. */
class TaskReadServiceViewAndZoneTest {

    private final TaskReadService service = new TaskReadService(
        null, null, null, null, null, null, Clock.systemUTC(), "America/New_York");

    @Test
    void unknownBlankOrMissingViewsFallBackToPending() {
        assertThat(TaskReadService.normalizeView(null)).isEqualTo("pending");
        assertThat(TaskReadService.normalizeView("")).isEqualTo("pending");
        assertThat(TaskReadService.normalizeView("bogus")).isEqualTo("pending");
        assertThat(TaskReadService.normalizeView("Pending")).isEqualTo("pending");
        assertThat(TaskReadService.normalizeView("assigned")).isEqualTo("assigned");
        assertThat(TaskReadService.normalizeView("completed")).isEqualTo("completed");
    }

    @Test
    void blankZoneUsesTheConfiguredDefaultAndExplicitZonesAreHonoured() {
        assertThat(service.zone(null)).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(service.zone("")).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(service.zone("  ")).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(service.zone("Asia/Tokyo")).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(service.zone("UTC")).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void unknownZonesAreRejectedAsBadRequests() {
        assertThatThrownBy(() -> service.zone("Mars/Olympus"))
            .isInstanceOf(InvalidSearchQueryException.class)
            .hasMessageContaining("Mars/Olympus");
    }

    @Test
    void invalidDefaultZoneFailsFastAtConstruction() {
        assertThatThrownBy(() -> new TaskReadService(null, null, null, null, null, null, Clock.systemUTC(), "Nowhere"))
            .isInstanceOf(java.time.DateTimeException.class);
    }
}
