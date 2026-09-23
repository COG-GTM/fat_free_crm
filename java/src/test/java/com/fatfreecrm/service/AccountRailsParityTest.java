package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fatfreecrm.config.PaginationProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the Java port to the Rails source it was derived from, by reading the Ruby files in the
 * repository rather than restating the Java constants. Skipped when the Rails tree is not
 * present next to the Java module (e.g. once the module is extracted to its own repository).
 *
 * <ul>
 *   <li>{@code app/models/entities/account.rb}: {@code sortable by: [...], default: "..."} and
 *       {@code self.per_page}.</li>
 *   <li>{@code app/controllers/entities_controller.rb#per_page_param}: {@code [1, [per_page, 200].min].max}.</li>
 *   <li>{@code app/controllers/application_controller.rb#auto_complete}: {@code .limit(10)}.</li>
 * </ul>
 */
class AccountRailsParityTest {

    private static final Path RAILS_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path ACCOUNT_RB = RAILS_ROOT.resolve("app/models/entities/account.rb");
    private static final Path ENTITIES_CONTROLLER_RB = RAILS_ROOT.resolve("app/controllers/entities_controller.rb");
    private static final Path APPLICATION_CONTROLLER_RB = RAILS_ROOT.resolve("app/controllers/application_controller.rb");

    @Test
    void sortWhitelistAndDefaultMatchRailsSortableDeclaration() {
        String source = read(ACCOUNT_RB);
        Matcher sortable = Pattern.compile("sortable by: \\[([^\\]]*)\\], default: \"([^\"]*)\"").matcher(source);
        assertThat(sortable.find()).as("sortable declaration in account.rb").isTrue();

        List<String> railsClauses = Arrays.stream(sortable.group(1).split(","))
                .map(s -> s.trim().replace("\"", ""))
                .toList();
        List<String> javaClauses = Arrays.stream(AccountSort.values()).map(AccountSort::railsValue).toList();

        assertThat(javaClauses).containsExactlyElementsOf(railsClauses);
        assertThat(AccountSort.DEFAULT.railsValue()).isEqualTo(sortable.group(2));
        for (String clause : railsClauses) {
            assertThat(AccountSort.parse(clause).railsValue()).isEqualTo(clause);
            assertThat(clause).matches(AccountSort.PATTERN);
        }
    }

    @Test
    void sortByMapKeysAreAcceptedWithoutADirection() {
        String source = read(ACCOUNT_RB);
        Matcher sortable = Pattern.compile("sortable by: \\[([^\\]]*)\\]").matcher(source);
        assertThat(sortable.find()).isTrue();

        for (String clause : sortable.group(1).split(",")) {
            String railsClause = clause.trim().replace("\"", "");
            String sortByMapKey = railsClause.split(" ")[0];
            assertThat(AccountSort.parse(sortByMapKey).railsValue()).isEqualTo(railsClause);
        }
    }

    @Test
    void defaultPageSizeMatchesAccountPerPage() {
        Matcher perPage = Pattern.compile("def self\\.per_page\\s+(\\d+)").matcher(read(ACCOUNT_RB));
        assertThat(perPage.find()).as("Account.per_page in account.rb").isTrue();

        int railsPerPage = Integer.parseInt(perPage.group(1));
        assertThat(PaginationProperties.DEFAULT_PAGE_SIZE).isEqualTo(railsPerPage);
        assertThat(new PaginationProperties(0, 0).clampPageSize(null)).isEqualTo(railsPerPage);
    }

    @Test
    void perPageClampMatchesEntitiesControllerPerPageParam() {
        Matcher clamp = Pattern.compile("\\[(\\d+), \\[per_page, (\\d+)\\]\\.min\\]\\.max").matcher(read(ENTITIES_CONTROLLER_RB));
        assertThat(clamp.find()).as("per_page_param clamp in entities_controller.rb").isTrue();

        int railsMin = Integer.parseInt(clamp.group(1));
        int railsMax = Integer.parseInt(clamp.group(2));
        PaginationProperties defaults = new PaginationProperties(0, 0);
        assertThat(PaginationProperties.MAX_PAGE_SIZE).isEqualTo(railsMax);
        assertThat(defaults.clampPageSize(railsMin - 1)).isEqualTo(railsMin);
        assertThat(defaults.clampPageSize(Integer.MIN_VALUE)).isEqualTo(railsMin);
        assertThat(defaults.clampPageSize(railsMax + 1)).isEqualTo(railsMax);
        assertThat(defaults.clampPageSize(Integer.MAX_VALUE)).isEqualTo(railsMax);
    }

    @Test
    void autocompleteLimitMatchesApplicationControllerAutoComplete() {
        Matcher limit = Pattern.compile("text_search\\(@query\\).*\\.limit\\((\\d+)\\)").matcher(read(APPLICATION_CONTROLLER_RB));
        assertThat(limit.find()).as("auto_complete limit in application_controller.rb").isTrue();

        assertThat(AccountService.AUTOCOMPLETE_LIMIT).isEqualTo(Integer.parseInt(limit.group(1)));
    }

    private static String read(Path path) {
        assumeTrue(Files.isRegularFile(path), "Rails source not present: " + path);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + path, e);
        }
    }
}
