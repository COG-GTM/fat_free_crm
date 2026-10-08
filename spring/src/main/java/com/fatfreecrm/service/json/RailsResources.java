package com.fatfreecrm.service.json;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Field;
import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.ResearchTool;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.Version;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.UserRepository;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RailsResources {

    /** AB-271 custom_fields JSONB is ignored by Rails across the six CRM entity resources. */
    public static final Set<String> CRM_EXCLUDED_COLUMNS = Set.of("custom_fields");

    public final RailsResource account;
    public final RailsResource user;
    public final RailsResource group;
    public final RailsResource field;
    public final RailsResource tag;
    public final RailsResource researchTool;
    public final RailsResource version;

    public RailsResources(AccountRepository accountRepository, UserRepository userRepository) {
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
}
