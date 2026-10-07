package com.fatfreecrm.service.json;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.UserRepository;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RailsResources {

    public final RailsResource account;

    public RailsResources(AccountRepository accountRepository, UserRepository userRepository) {
        account = new RailsResource(
            "Account",
            "accounts",
            Account.class,
            Set.of("subscribed_users"),
            true,
            Set.of(),
            "accounts",
            entity -> ((Account) entity).getName(),
            Map.of("users", new RailsResource.RelatedExclusion(userId -> {
                if (!userRepository.existsById(userId)) {
                    return Set.of();
                }
                return Set.copyOf(accountRepository.findIdsByUserId(userId));
            }))
        );
    }
}
