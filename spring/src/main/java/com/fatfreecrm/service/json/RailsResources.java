package com.fatfreecrm.service.json;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Field;
import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.ResearchTool;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.Version;
import com.fatfreecrm.repository.AccountContactRepository;
import com.fatfreecrm.repository.AccountOpportunityRepository;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.CampaignRepository;
import com.fatfreecrm.repository.ContactOpportunityRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.LeadRepository;
import com.fatfreecrm.repository.OpportunityRepository;
import com.fatfreecrm.repository.TaskRepository;
import com.fatfreecrm.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RailsResources {

    /** AB-271 custom_fields JSONB is ignored by Rails across the six CRM entity resources. */
    public static final Set<String> CRM_EXCLUDED_COLUMNS = Set.of("custom_fields");

    public final RailsResource account;
    public final RailsResource campaign;
    public final RailsResource contact;
    public final RailsResource lead;
    public final RailsResource opportunity;
    public final RailsResource task;
    public final RailsResource comment;
    public final RailsResource user;
    public final RailsResource group;
    public final RailsResource field;
    public final RailsResource tag;
    public final RailsResource researchTool;
    public final RailsResource version;

    public RailsResources(
        AccountRepository accountRepository,
        AccountContactRepository accountContactRepository,
        AccountOpportunityRepository accountOpportunityRepository,
        CampaignRepository campaignRepository,
        ContactOpportunityRepository contactOpportunityRepository,
        ContactRepository contactRepository,
        LeadRepository leadRepository,
        OpportunityRepository opportunityRepository,
        TaskRepository taskRepository,
        UserRepository userRepository
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
        contact = new RailsResource(
            "Contact",
            "contacts",
            Contact.class,
            Set.of("subscribed_users"),
            true,
            CRM_EXCLUDED_COLUMNS,
            "contacts",
            entity -> Objects.toString(((Contact) entity).getFirstName(), "")
                + " " + Objects.toString(((Contact) entity).getLastName(), ""),
            Map.of(
                "accounts", new RailsResource.RelatedExclusion(accountId -> {
                    if (!accountRepository.existsById(accountId)) {
                        return Set.of();
                    }
                    return Set.copyOf(accountContactRepository.findContactIdsByAccountId(accountId));
                }),
                "opportunities", new RailsResource.RelatedExclusion(opportunityId -> {
                    if (!opportunityRepository.existsById(opportunityId)) {
                        return Set.of();
                    }
                    return Set.copyOf(contactOpportunityRepository.findContactIdsByOpportunityId(opportunityId));
                }),
                "users", new RailsResource.RelatedExclusion(userId -> {
                    if (!userRepository.existsById(userId)) {
                        return Set.of();
                    }
                    return Set.copyOf(contactRepository.findIdsByUserId(userId));
                })
            )
        );
        lead = new RailsResource(
            "Lead",
            "leads",
            Lead.class,
            Set.of("subscribed_users"),
            true,
            CRM_EXCLUDED_COLUMNS,
            "leads",
            entity -> Objects.toString(((Lead) entity).getFirstName(), "")
                + " " + Objects.toString(((Lead) entity).getLastName(), ""),
            Map.of(
                "campaigns", new RailsResource.RelatedExclusion(campaignId -> {
                    if (!campaignRepository.existsById(campaignId)) {
                        return Set.of();
                    }
                    return Set.copyOf(leadRepository.findIdsByCampaignId(campaignId));
                }),
                "users", new RailsResource.RelatedExclusion(userId -> {
                    if (!userRepository.existsById(userId)) {
                        return Set.of();
                    }
                    return Set.copyOf(leadRepository.findIdsByUserId(userId));
                })
            )
        );
        opportunity = new RailsResource(
            "Opportunity",
            "opportunities",
            Opportunity.class,
            Set.of("subscribed_users"),
            true,
            CRM_EXCLUDED_COLUMNS,
            "opportunities",
            entity -> ((Opportunity) entity).getName(),
            Map.of(
                "users", new RailsResource.RelatedExclusion(userId -> {
                    if (!userRepository.existsById(userId)) {
                        return Set.of();
                    }
                    return Set.copyOf(opportunityRepository.findIdsByUserId(userId));
                }),
                "accounts", new RailsResource.RelatedExclusion(accountId -> {
                    if (!accountRepository.existsById(accountId)) {
                        return Set.of();
                    }
                    return Set.copyOf(accountOpportunityRepository.findOpportunityIdsByAccountId(accountId));
                }),
                "contacts", new RailsResource.RelatedExclusion(contactId -> {
                    if (!contactRepository.existsById(contactId)) {
                        return Set.of();
                    }
                    return Set.copyOf(contactOpportunityRepository.findOpportunityIdsByContactId(contactId));
                }),
                "campaigns", new RailsResource.RelatedExclusion(campaignId -> {
                    if (!campaignRepository.existsById(campaignId)) {
                        return Set.of();
                    }
                    return Set.copyOf(opportunityRepository.findIdsByCampaignId(campaignId));
                })
            )
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
        user = new RailsResource(
            "User",
            "users",
            User.class,
            Set.of(),
            false,
            Set.of(
                "encrypted_password", "password_salt", "last_sign_in_at", "current_sign_in_at",
                "last_sign_in_ip", "current_sign_in_ip", "sign_in_count", "unconfirmed_email",
                "reset_password_token", "reset_password_sent_at", "remember_token", "remember_created_at",
                "confirmation_token", "confirmed_at", "confirmation_sent_at"
            ),
            "users",
            entity -> ((User) entity).getUsername(),
            Map.of()
        );
        group = new RailsResource(
            "Group", "groups", Group.class, Set.of(), false, Set.of(), "groups", entity -> null, Map.of()
        );
        field = new RailsResource(
            "Field", "fields", Field.class, Set.of("collection"), false, Set.of("type"), "fields",
            entity -> null, Map.of()
        );
        tag = new RailsResource(
            "Tag", "tags", Tag.class, Set.of(), false, Set.of(), "tags", entity -> null, Map.of()
        );
        researchTool = new RailsResource(
            "ResearchTool", "research_tools", ResearchTool.class, Set.of(), false, Set.of(),
            "research_tools", entity -> null, Map.of()
        );
        version = new RailsResource(
            "Version", "versions", Version.class, Set.of(), false, Set.of(), "activities", entity -> null, Map.of()
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
