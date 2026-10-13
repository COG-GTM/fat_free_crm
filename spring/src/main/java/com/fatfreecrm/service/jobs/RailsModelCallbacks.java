package com.fatfreecrm.service.jobs;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.service.mail.AssignmentNotificationService;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this service."
)
public class RailsModelCallbacks {

    private static final Pattern RUBY_INTEGER = Pattern.compile("^\\s*([+-]?\\d+)");

    private final UserRepository userRepository;
    private final AssignmentNotificationService assignmentNotifications;
    private final AccountEnrichmentTrigger accountEnrichment;

    public RailsModelCallbacks(
        UserRepository userRepository,
        AssignmentNotificationService assignmentNotifications,
        AccountEnrichmentTrigger accountEnrichment
    ) {
        this.userRepository = userRepository;
        this.assignmentNotifications = assignmentNotifications;
        this.accountEnrichment = accountEnrichment;
    }

    @Transactional
    public void afterCreate(CrmEntity entity, RailsWhodunnit whodunnit) {
        assignmentNotifications.afterCreate(entity, findWhodunnit(whodunnit));
        if (entity instanceof Account account) {
            accountEnrichment.afterAccountSaved(account, null, null);
        }
    }

    @Transactional
    public void afterUpdate(
        CrmEntity entity,
        Long previousAssignedToId,
        String previousWebsite,
        String previousWikidataId,
        RailsWhodunnit whodunnit
    ) {
        assignmentNotifications.afterUpdate(entity, previousAssignedToId, findWhodunnit(whodunnit));
        if (entity instanceof Account account) {
            accountEnrichment.afterAccountSaved(account, previousWebsite, previousWikidataId);
        }
    }

    private User findWhodunnit(RailsWhodunnit whodunnit) {
        Objects.requireNonNull(whodunnit);
        if (whodunnit instanceof RailsWhodunnit.UserValue userValue) {
            return userRepository.findById(userValue.userId()).orElse(null);
        }
        if (whodunnit instanceof RailsWhodunnit.StringValue stringValue) {
            Matcher matcher = RUBY_INTEGER.matcher(stringValue.userId());
            long userId = matcher.find() ? parseRubyInteger(matcher.group(1)) : 0;
            return userRepository.findById(userId).orElse(null);
        }
        return null;
    }

    private static long parseRubyInteger(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
