package com.fatfreecrm.service.json;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.repository.AccountContactRepository;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.CampaignRepository;
import com.fatfreecrm.repository.ContactOpportunityRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.LeadRepository;
import com.fatfreecrm.repository.OpportunityRepository;
import com.fatfreecrm.repository.UserRepository;
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

    public RailsResources(
        AccountRepository accountRepository,
        AccountContactRepository accountContactRepository,
        CampaignRepository campaignRepository,
        ContactOpportunityRepository contactOpportunityRepository,
        ContactRepository contactRepository,
        LeadRepository leadRepository,
        OpportunityRepository opportunityRepository,
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
    }
}
