package com.fatfreecrm.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.repository.TaskRepository;
import com.fatfreecrm.security.authz.AccessPolicy;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.query.InvalidSearchQueryException;
import com.fatfreecrm.service.query.SearchableEntities;
import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Rails {@code TasksController#view} ({@code Task::ALLOWED_VIEWS.include?(view) ? view : views.first}) and the
 * session {@code Time.zone} resolution that feeds the bucket windows.
 */
class TaskReadServiceViewAndZoneTest {

    private static TaskReadService service(String defaultZone) {
        return new TaskReadService(
            mock(TaskRepository.class),
            mock(SettingRepository.class),
            mock(AccessPolicy.class),
            mock(RailsJsonWriter.class),
            mock(RailsResources.class),
            mock(SearchableEntities.class),
            Clock.systemUTC(),
            defaultZone
        );
    }

    @Test
    void normalizeViewAcceptsOnlyRailsAllowedViews() {
        assertThat(TaskReadService.normalizeView("pending")).isEqualTo("pending");
        assertThat(TaskReadService.normalizeView("assigned")).isEqualTo("assigned");
        assertThat(TaskReadService.normalizeView("completed")).isEqualTo("completed");
    }

    @Test
    void unknownBlankAndNullViewsFallBackToPending() {
        assertThat(TaskReadService.normalizeView(null)).isEqualTo("pending");
        assertThat(TaskReadService.normalizeView("")).isEqualTo("pending");
        assertThat(TaskReadService.normalizeView("bogus")).isEqualTo("pending");
        assertThat(TaskReadService.normalizeView("Pending")).as("Rails comparison is case-sensitive")
            .isEqualTo("pending");
        assertThat(TaskReadService.normalizeView(" pending")).isEqualTo("pending");
    }

    @Test
    void zoneDefaultsToConfiguredZoneWhenAbsentOrBlank() {
        TaskReadService service = service("America/Sao_Paulo");
        assertThat(service.zone(null)).isEqualTo(ZoneId.of("America/Sao_Paulo"));
        assertThat(service.zone("")).isEqualTo(ZoneId.of("America/Sao_Paulo"));
        assertThat(service.zone("   ")).isEqualTo(ZoneId.of("America/Sao_Paulo"));
    }

    @Test
    void zoneHonoursExplicitIanaIdentifiers() {
        TaskReadService service = service("UTC");
        assertThat(service.zone("Asia/Tokyo")).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(service.zone("Etc/UTC")).isEqualTo(ZoneId.of("Etc/UTC"));
    }

    @Test
    void unknownZoneIsAnInvalidSearchQueryNamingTheParameter() {
        TaskReadService service = service("UTC");
        assertThatThrownBy(() -> service.zone("Mars/Olympus_Mons"))
            .isInstanceOf(InvalidSearchQueryException.class)
            .hasMessageContaining("Unknown time zone: Mars/Olympus_Mons");
    }
}
