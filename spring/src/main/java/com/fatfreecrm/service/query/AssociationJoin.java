package com.fatfreecrm.service.query;

import com.fatfreecrm.domain.AccountContact;
import com.fatfreecrm.domain.AccountOpportunity;
import com.fatfreecrm.domain.Address;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.ContactOpportunity;
import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.Tagging;
import com.fatfreecrm.domain.Task;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import org.hibernate.query.criteria.JpaEntityJoin;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.sqm.tree.SqmJoinType;

/**
 * One hop in a Ransack association path. Implemented as Hibernate entity joins plus
 * {@code on(...)} predicates so the generated SQL matches the Rails LEFT OUTER JOINs exactly.
 */
@FunctionalInterface
public interface AssociationJoin {

    From<?, ?> join(From<?, ?> source, CriteriaBuilder cb, String railsSourceType);

    @SuppressWarnings("unchecked")
    private static <X> JpaEntityJoin<X> entityJoin(From<?, ?> from, Class<X> type) {
        return ((JpaFrom<?, ?>) from).join(type, SqmJoinType.LEFT);
    }

    /** {@code join_table ON join_table.srcColumn = source.id -> target ON target.id = join_table.targetColumn}. */
    static AssociationJoin joinTable(Class<?> joinTableEntity, String sourceAttribute, String targetAttribute) {
        return (source, cb, railsType) -> {
            JpaEntityJoin<?> joinTable = entityJoin(source, joinTableEntity);
            joinTable.on(cb.equal(joinTable.get(sourceAttribute), source));
            return joinTable.join(targetAttribute, JoinType.LEFT);
        };
    }

    /** {@code target ON target.fkColumn = source.id} (e.g. {@code contacts.lead_id = leads.id}). */
    static AssociationJoin reverseForeignKey(Class<?> targetEntity, String foreignKeyAttribute) {
        return (source, cb, railsType) -> {
            JpaEntityJoin<?> target = entityJoin(source, targetEntity);
            target.on(cb.equal(target.get(foreignKeyAttribute), source));
            return target;
        };
    }

    /** {@code target ON target.id = source.fkColumn} (e.g. {@code campaigns ON id = leads.campaign_id}). */
    static AssociationJoin foreignKey(Class<?> targetEntity, String foreignKeyAttribute) {
        return (source, cb, railsType) -> {
            JpaEntityJoin<?> target = entityJoin(source, targetEntity);
            target.on(cb.equal(target, source.get(foreignKeyAttribute)));
            return target;
        };
    }

    /**
     * {@code target ON target.polyType = '<RailsModel>' AND target.polyId = source.id}
     * for the tasks/addresses/emails/comments polymorphic associations.
     */
    static AssociationJoin polymorphic(Class<?> targetEntity, String typeAttribute, String idAttribute) {
        return (source, cb, railsType) -> {
            JpaEntityJoin<?> target = entityJoin(source, targetEntity);
            target.on(cb.and(
                cb.equal(target.get(typeAttribute), railsType),
                cb.equal(target.get(idAttribute).as(Long.class), source.get("id"))
            ));
            return target;
        };
    }

    /** {@code taggings ON taggable_type = '<Model>' AND context = 'tags' AND taggable_id = id -> tags}. */
    static AssociationJoin tags() {
        return (source, cb, railsType) -> {
            JpaEntityJoin<Tagging> tagging = entityJoin(source, Tagging.class);
            tagging.on(cb.and(
                cb.equal(tagging.get("taggableType"), railsType),
                cb.equal(tagging.get("context"), "tags"),
                cb.equal(tagging.get("taggableId").as(Long.class), source.get("id"))
            ));
            return tagging.join("tag", JoinType.LEFT);
        };
    }

    static AssociationJoin accountContacts() {
        return joinTable(AccountContact.class, "account", "contact");
    }

    static AssociationJoin accountOpportunities() {
        return joinTable(AccountOpportunity.class, "account", "opportunity");
    }

    static AssociationJoin contactOpportunities(String sourceAttribute, String targetAttribute) {
        return joinTable(ContactOpportunity.class, sourceAttribute, targetAttribute);
    }

    static AssociationJoin tasks() {
        return polymorphic(Task.class, "assetType", "assetId");
    }

    static AssociationJoin addresses() {
        return polymorphic(Address.class, "addressableType", "addressableId");
    }

    static AssociationJoin emails() {
        return polymorphic(Email.class, "mediatorType", "mediatorId");
    }

    static AssociationJoin comments() {
        return polymorphic(Comment.class, "commentableType", "commentableId");
    }
}
