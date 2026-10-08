package com.fatfreecrm.service.json;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.repository.AccountOpportunityRepository;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.CampaignRepository;
import com.fatfreecrm.repository.ContactOpportunityRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.OpportunityRepository;
import com.fatfreecrm.repository.UserRepository;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RailsResources {

    /** AB-271 custom_fields JSONB is ignored by Rails across the six CRM entity resources. */
    public static final Set<String> CRM_EXCLUDED_COLUMNS = Set.of("custom_fields");

    public final RailsResource account;
    public final RailsResource opportunity;

    public RailsResources(
        AccountRepository accountRepository,
        UserRepository userRepository,
        OpportunityRepository opportunityRepository,
        AccountOpportunityRepository accountOpportunityRepository,
        ContactOpportunityRepository contactOpportunityRepository,
        ContactRepository contactRepository,
        CampaignRepository campaignRepository
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
    }
}
