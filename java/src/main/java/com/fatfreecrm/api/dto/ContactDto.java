package com.fatfreecrm.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * JSON representation of a contact, matching the {@code Contact} component schema in
 * docs/migration/openapi.yaml field-for-field and in the same order (ActiveModel
 * {@code to_json} of the Rails record, minus dynamic {@code cf_*} custom-field columns).
 *
 * <p>Property names are snake_case; {@code null} columns are emitted as JSON {@code null}
 * (never omitted), as ActiveModel does. Timestamps are ISO-8601 without a zone designator
 * because Rails stores them in {@code timestamp without time zone} columns (UTC by
 * convention). {@code born_on} is an ISO date.
 *
 * <p>{@code subscribed_users} is stored by Rails as YAML text
 * ({@code serialize :subscribed_users, type: Array}). Only the trivial array form written by
 * Rails is decoded, without a YAML library:
 * <pre>
 * ---
 * - 1
 * - 2
 * </pre>
 * yields {@code [1, 2]}; {@code NULL}, blank text and {@code --- []} yield {@code []}. Any other
 * shape (unexpected YAML, non-integer items) also yields {@code []} rather than failing the
 * request. See {@link ContactMapper#parseSubscribedUsers(String)}.
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
        @JsonFormat(pattern = TIMESTAMP_PATTERN) LocalDateTime deletedAt,
        @JsonFormat(pattern = TIMESTAMP_PATTERN) LocalDateTime createdAt,
        @JsonFormat(pattern = TIMESTAMP_PATTERN) LocalDateTime updatedAt) {

    /** ISO-8601 local date-time with millisecond precision, e.g. {@code 2024-05-01T09:30:00.000}. */
    public static final String TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS";
}
