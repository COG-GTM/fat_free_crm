package com.fatfreecrm.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Rails {@code Account} JSON shape (snake_case, millisecond UTC timestamps). */
public record AccountResponse(
    @JsonProperty("id") Long id,
    @JsonProperty("user_id") Long userId,
    @JsonProperty("assigned_to") Long assignedTo,
    @JsonProperty("name") String name,
    @JsonProperty("access") String access,
    @JsonProperty("website") String website,
    @JsonProperty("toll_free_phone") String tollFreePhone,
    @JsonProperty("phone") String phone,
    @JsonProperty("fax") String fax,
    @JsonProperty("email") String email,
    @JsonProperty("background_info") String backgroundInfo,
    @JsonProperty("rating") Integer rating,
    @JsonProperty("category") String category,
    @JsonProperty("subscribed_users") List<Long> subscribedUsers,
    @JsonProperty("contacts_count") Integer contactsCount,
    @JsonProperty("opportunities_count") Integer opportunitiesCount,
    @JsonProperty("wikidata_id") String wikidataId,
    @JsonProperty("latitude") BigDecimal latitude,
    @JsonProperty("longitude") BigDecimal longitude,
    @JsonProperty("blog") String blog,
    @JsonProperty("linkedin") String linkedin,
    @JsonProperty("facebook") String facebook,
    @JsonProperty("twitter") String twitter,
    @JsonProperty("bluesky") String bluesky,
    @JsonProperty("instagram") String instagram,
    @JsonProperty("mastodon") String mastodon,
    @JsonProperty("deleted_at") String deletedAt,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt,
    @JsonProperty("tag_list") List<String> tagList
) {

    public AccountResponse {
        subscribedUsers = List.copyOf(subscribedUsers);
        tagList = List.copyOf(tagList);
    }

    private static final DateTimeFormatter RAILS_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public static AccountResponse from(Account account, List<String> tagList) {
        return new AccountResponse(
            account.getId(),
            userId(account.getUser()),
            userId(account.getAssignedTo()),
            account.getName(),
            account.getAccess(),
            account.getWebsite(),
            account.getTollFreePhone(),
            account.getPhone(),
            account.getFax(),
            account.getEmail(),
            account.getBackgroundInfo(),
            account.getRating(),
            account.getCategory(),
            List.copyOf(account.getSubscribedUsers()),
            account.getContactsCount(),
            account.getOpportunitiesCount(),
            account.getWikidataId(),
            account.getLatitude(),
            account.getLongitude(),
            account.getBlog(),
            account.getLinkedin(),
            account.getFacebook(),
            account.getTwitter(),
            account.getBluesky(),
            account.getInstagram(),
            account.getMastodon(),
            timestamp(account.getDeletedAt()),
            timestamp(account.getCreatedAt()),
            timestamp(account.getUpdatedAt()),
            List.copyOf(tagList)
        );
    }

    private static Long userId(User user) {
        return user == null ? null : user.getId();
    }

    private static String timestamp(Instant instant) {
        return instant == null ? null : RAILS_TIMESTAMP.format(instant);
    }
}
