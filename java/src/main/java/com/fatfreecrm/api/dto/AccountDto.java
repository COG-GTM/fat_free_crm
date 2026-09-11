package com.fatfreecrm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * JSON representation of an account, matching the {@code Account} component schema of
 * docs/migration/openapi.yaml (Rails' default {@code ActiveModel#to_json}) field-for-field and
 * in the same order: snake_case names, {@code null}s emitted rather than omitted.
 *
 * <p>Deltas from Rails, all documented in the OpenAPI schema:
 * <ul>
 *   <li>timestamps are RFC 3339 / ISO-8601 in UTC (Rails stores UTC without a zone; the DTO
 *       re-attaches the {@code Z} offset);</li>
 *   <li>{@code latitude}/{@code longitude} are JSON numbers as the schema says (Rails would
 *       emit decimals as strings);</li>
 *   <li>{@code subscribed_users} is an array of user ids decoded from the YAML column by
 *       {@link SubscribedUsersParser}; unparseable text becomes {@code []};</li>
 *   <li>dynamic {@code cf_*} custom-field columns are not included.</li>
 * </ul>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AccountDto(
        long id,
        Long userId,
        Long assignedTo,
        String name,
        String access,
        String website,
        String tollFreePhone,
        String phone,
        String fax,
        String email,
        String backgroundInfo,
        int rating,
        String category,
        List<Long> subscribedUsers,
        Integer contactsCount,
        Integer opportunitiesCount,
        String wikidataId,
        BigDecimal latitude,
        BigDecimal longitude,
        String blog,
        String linkedin,
        String facebook,
        String twitter,
        String bluesky,
        String instagram,
        String mastodon,
        OffsetDateTime deletedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
