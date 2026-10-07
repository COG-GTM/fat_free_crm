package com.fatfreecrm.service.json;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.CampaignRepository;
import com.fatfreecrm.repository.UserRepository;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RailsResources {

    /** AB-271 custom_fields JSONB is ignored by Rails across the six CRM entity resources. */
    public static final Set<String> CRM_EXCLUDED_COLUMNS = Set.of("custom_fields");

    public final RailsResource account;
    public final RailsResource campaign;

    public RailsResources(
        AccountRepository accountRepository,
        UserRepository userRepository,
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
    }
}
