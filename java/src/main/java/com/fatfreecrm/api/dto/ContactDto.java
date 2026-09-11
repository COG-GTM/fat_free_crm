package com.fatfreecrm.api.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * JSON representation of a contact, matching the {@code Contact} component schema in
 * docs/migration/openapi.yaml field-for-field and in the same order (ActiveModel
 * {@code to_json} of the Rails record, minus dynamic {@code cf_*} custom-field columns).
 *
 * <p>Property names are snake_case; {@code null} columns are emitted as JSON {@code null}
 * (never omitted), as ActiveModel does. Timestamps are ISO-8601 UTC ({@code ...Z}); Rails stores
 * them in {@code timestamp without time zone} columns that hold UTC by convention.
 * {@code born_on} is an ISO date.
 *
 * <p>{@code subscribed_users} is stored by Rails as YAML text and decoded by
 * {@link SubscribedUsersParser}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ContactDto(
        long id,
        Long userId,
        Long leadId,
        Long assignedTo,
        Long reportsTo,
        String firstName,
        String lastName,
        String access,
        String title,
        String department,
        String source,
        String email,
        String altEmail,
        String phone,
        String mobile,
        String fax,
        String blog,
        String linkedin,
        String facebook,
        String twitter,
        String zoom,
        String teams,
        String signal,
        String instagram,
        String mastodon,
        String bluesky,
        LocalDate bornOn,
        boolean doNotCall,
        String backgroundInfo,
        List<Long> subscribedUsers,
        OffsetDateTime deletedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
