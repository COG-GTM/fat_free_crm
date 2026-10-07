package com.fatfreecrm.service.json;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.CampaignRepository;
import com.fatfreecrm.repository.TaskRepository;
import com.fatfreecrm.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RailsResources {

    /** AB-271 custom_fields JSONB is ignored by Rails across the six CRM entity resources. */
    public static final Set<String> CRM_EXCLUDED_COLUMNS = Set.of("custom_fields");

    public final RailsResource account;
    public final RailsResource campaign;

    public final RailsResource task;

    public final RailsResource comment;

    public RailsResources(
        AccountRepository accountRepository,
        UserRepository userRepository,
        CampaignRepository campaignRepository,
        TaskRepository taskRepository
    ) {
        account = new RailsResource(
            "Account",
            "accounts",
            Account.class,
            Set.of("subscribed_users"),
            true,
            CRM_EXCLUDED_COLUMNS,
            "accounts",
            entity -> ((Account) entity).getName(),
            Map.of("users", new RailsResource.RelatedExclusion(userId -> {
                if (!userRepository.existsById(userId)) {
                    return Set.of();
                }
                return Set.copyOf(accountRepository.findIdsByUserId(userId));
            }))
        );
        campaign = new RailsResource(
            "Campaign",
            "campaigns",
            Campaign.class,
            Set.of("subscribed_users"),
            true,
            CRM_EXCLUDED_COLUMNS,
            "campaigns",
            entity -> ((Campaign) entity).getName(),
            Map.of("users", new RailsResource.RelatedExclusion(userId -> {
                if (!userRepository.existsById(userId)) {
                    return Set.of();
                }
                return Set.copyOf(campaignRepository.findIdsByUserId(userId));
            }))
        );
        task = new RailsResource(
            "Task",
            "tasks",
            Task.class,
            Set.of("subscribed_users"),
            false,
            CRM_EXCLUDED_COLUMNS,
            "tasks",
            entity -> ((Task) entity).getName(),
            taskRelatedExclusions(taskRepository)
        );
        comment = new RailsResource(
            "Comment",
            "comments",
            Comment.class,
            Set.of(),
            false,
            Set.of(),
            "comments",
            entity -> ((Comment) entity).getTitle(),
            Map.of()
        );
    }

    /** Rails {@code related.classify.constantize.find_by_id(id).tasks}: singular or plural asset names. */
    private static Map<String, RailsResource.RelatedExclusion> taskRelatedExclusions(TaskRepository taskRepository) {
        Map<String, RailsResource.RelatedExclusion> exclusions = new LinkedHashMap<>();
        Map<String, String> assets = Map.of(
            "account", "Account", "campaign", "Campaign", "contact", "Contact", "lead", "Lead",
            "opportunity", "Opportunity");
        assets.forEach((name, railsModel) -> {
            RailsResource.RelatedExclusion exclusion = new RailsResource.RelatedExclusion(assetId ->
                assetId > Integer.MAX_VALUE || assetId < Integer.MIN_VALUE
                    ? Set.of()
                    : taskRepository.findByAssetTypeAndAssetId(railsModel, assetId.intValue()).stream()
                        .map(Task::getId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            exclusions.put(name, exclusion);
            exclusions.put("opportunity".equals(name) ? "opportunities" : name + "s", exclusion);
        });
        return Map.copyOf(exclusions);
    }
}
