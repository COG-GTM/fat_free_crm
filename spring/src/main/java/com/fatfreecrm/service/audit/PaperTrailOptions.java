package com.fatfreecrm.service.audit;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-model mirror of the Rails {@code has_paper_trail} options for the AB-272 write families:
 * the {@code versions.item_type} Rails class name, the ignored attributes (present in
 * {@code object}, excluded from {@code object_changes}, and not counted as notable changes), and
 * the polymorphic association copied to {@code versions.related_type}/{@code related_id} via
 * {@code meta: {related: ...}}. {@code List} has no paper trail in Rails.
 */
public record PaperTrailOptions(RailsModelType itemType, Set<String> ignore, String relatedAttribute) {

    private static final Map<Class<?>, PaperTrailOptions> REGISTRY = Map.of(
        Task.class, new PaperTrailOptions(RailsModelType.TASK, Set.of("subscribed_users"), "asset"),
        Comment.class, new PaperTrailOptions(RailsModelType.COMMENT, Set.of("state"), "commentable"),
        Email.class, new PaperTrailOptions(RailsModelType.EMAIL, Set.of("state"), "mediator"),
        // Commentable entities (app/models/{account,contact,lead,opportunity,campaign}.rb):
        // all declare ignore: [:subscribed_users] — their entry exists so that a commentable.save
        // during comment subscription can evaluate notability; with only subscribed_users +
        // updated_at changed, PaperTrail writes no version.
        Account.class, new PaperTrailOptions(RailsModelType.ACCOUNT, Set.of("subscribed_users"), null),
        Contact.class, new PaperTrailOptions(RailsModelType.CONTACT, Set.of("subscribed_users"), null),
        Lead.class, new PaperTrailOptions(RailsModelType.LEAD, Set.of("subscribed_users"), null),
        Opportunity.class, new PaperTrailOptions(RailsModelType.OPPORTUNITY,
            Set.of("subscribed_users"), null),
        Campaign.class, new PaperTrailOptions(RailsModelType.CAMPAIGN, Set.of("subscribed_users"),
            null),
        // app/models/users/user.rb: has_paper_trail ignore: [:last_sign_in_at] (AB-272 Phase B admin).
        User.class, new PaperTrailOptions(RailsModelType.USER, Set.of("last_sign_in_at"), null)
    );

    public static Optional<PaperTrailOptions> forClass(Class<?> entityClass) {
        return Optional.ofNullable(REGISTRY.get(entityClass));
    }
}
