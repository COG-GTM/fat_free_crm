package com.fatfreecrm.service.read;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.repository.CommentRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.authz.AccessPolicy;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.query.InvalidSearchQueryException;
import com.fatfreecrm.service.query.RubyScalars;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

/**
 * Rails {@code CommentsController#index}: the first {@code *_id} parameter names the commentable, which must be
 * visible through {@code Klass.my(current_user)}; its comments are then listed newest first. Without a
 * commentable, Rails lists {@code Comment.accessible_by(ability)} (the AB-268 {@code user_id} rule).
 */
@Service
public class CommentReadService {

    private static final Map<String, Commentable> COMMENTABLES = Map.of(
        "account", new Commentable("Account", Account.class),
        "campaign", new Commentable("Campaign", Campaign.class),
        "contact", new Commentable("Contact", Contact.class),
        "lead", new Commentable("Lead", Lead.class),
        "opportunity", new Commentable("Opportunity", Opportunity.class),
        "task", new Commentable("Task", Task.class)
    );

    private final CommentRepository commentRepository;
    private final AccessPolicy accessPolicy;
    private final EntityManager entityManager;
    private final RailsJsonWriter jsonWriter;
    private final RailsResources railsResources;

    public CommentReadService(
        CommentRepository commentRepository,
        AccessPolicy accessPolicy,
        EntityManager entityManager,
        RailsJsonWriter jsonWriter,
        RailsResources railsResources
    ) {
        this.commentRepository = commentRepository;
        this.accessPolicy = accessPolicy;
        this.entityManager = entityManager;
        this.jsonWriter = jsonWriter;
        this.railsResources = railsResources;
    }

    @Transactional(readOnly = true)
    public List<ObjectNode> list(AuthenticatedUser user, MultiValueMap<String, String> params) {
        String key = params.keySet().stream().filter(name -> name.endsWith("_id")).findFirst().orElse(null);
        if (key == null) {
            List<Long> ids = commentRepository.findAll(accessPolicy.accessibleBy(user, Comment.class),
                Sort.by("id").ascending()).stream().map(Comment::getId).toList();
            return jsonWriter.write(railsResources.comment, ids);
        }
        Commentable commentable = COMMENTABLES.get(key.substring(0, key.length() - "_id".length()));
        if (commentable == null) {
            throw new InvalidSearchQueryException("Unknown commentable: " + key, List.of(key));
        }
        long id = RubyScalars.toLong(params.getFirst(key));
        if (id <= 0 || id > Integer.MAX_VALUE || !visible(user, commentable.type(), id)) {
            throw new EntityNotFoundException("The notes are not available.");
        }
        Specification<Comment> forCommentable = (root, query, cb) -> cb.and(
            cb.equal(root.get("commentableType"), commentable.railsName()),
            cb.equal(root.get("commentableId"), (int) id));
        List<Long> ids = commentRepository.findAll(forCommentable, Sort.by("createdAt").descending())
            .stream().map(Comment::getId).toList();
        return jsonWriter.write(railsResources.comment, ids);
    }

    private <T> boolean visible(AuthenticatedUser user, Class<T> type, long id) {
        Specification<T> scope = accessPolicy.accessibleBy(user, type);
        if (type == Task.class) {
            @SuppressWarnings("unchecked")
            Specification<T> taskMy = (Specification<T>) TaskReadService.my(user.id());
            scope = scope.and(taskMy);
        }
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<T> root = query.from(type);
        Predicate accessible = scope.toPredicate(root, query, cb);
        Predicate byId = cb.equal(root.get("id"), id);
        query.select(cb.count(root)).where(accessible == null ? byId : cb.and(accessible, byId));
        return entityManager.createQuery(query).getSingleResult() > 0;
    }

    private record Commentable(String railsName, Class<?> type) {
    }
}
