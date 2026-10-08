package com.fatfreecrm.service.query;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.domain.support.RailsYaml;
import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.authz.AccessPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Registry of the five CRM entities that support list search.
 * Task and User lists are intentionally out of scope for AB-269 (handled by AB-270).
 */
@Component
public class SearchableEntities {

    private static final int DEFAULT_PER_PAGE = 20;

    private static final List<String> DEFAULT_ACCOUNT_CATEGORIES = List.of(
        "affiliate", "competitor", "customer", "partner", "reseller", "vendor");
    private static final List<String> DEFAULT_LEAD_STATUSES = List.of("new", "contacted", "converted", "rejected");

    private final Map<Class<?>, SearchableEntity> byClass;

    public SearchableEntities(
        EntityManager entityManager,
        SettingRepository settingRepository,
        AccessPolicy accessPolicy
    ) {
        Map<Class<?>, SearchableEntity> map = new LinkedHashMap<>();
        register(map, new SearchableEntity(
            Account.class,
            "Account",
            DEFAULT_PER_PAGE,
            SortWhitelist.of(
                Account.class, "created_at DESC", "name ASC", "rating DESC", "created_at DESC", "updated_at DESC"),
            Map.of(
                "contacts", def(AssociationJoin.accountContacts(), Contact.class),
                "opportunities", def(AssociationJoin.accountOpportunities(), Opportunity.class),
                "tasks", def(AssociationJoin.tasks(), com.fatfreecrm.domain.Task.class),
                "addresses", def(AssociationJoin.addresses(), com.fatfreecrm.domain.Address.class),
                "emails", def(AssociationJoin.emails(), com.fatfreecrm.domain.Email.class),
                "comments", def(AssociationJoin.comments(), com.fatfreecrm.domain.Comment.class),
                "tags", def(AssociationJoin.tags(), com.fatfreecrm.domain.Tag.class)
            ),
            (root, cb, query) -> cb.or(
                likeEscaped(root, cb, "name", query),
                likeEscaped(root, cb, "email", query)
            ),
            true,
            user -> Map.of("category", accountCategoryFacets(user, entityManager, settingRepository, accessPolicy)),
            accountStateFilter()
        ));
        register(map, new SearchableEntity(
            Contact.class,
            "Contact",
            DEFAULT_PER_PAGE,
            SortWhitelist.of(Contact.class,
                "created_at DESC", "first_name ASC", "last_name ASC", "created_at DESC", "updated_at DESC"),
            Map.of(
                "account", def(AssociationJoin.joinTable(
                    com.fatfreecrm.domain.AccountContact.class, "contact", "account"), Account.class),
                "opportunities", def(AssociationJoin.contactOpportunities("contact", "opportunity"), Opportunity.class),
                "tasks", def(AssociationJoin.tasks(), com.fatfreecrm.domain.Task.class),
                "addresses", def(AssociationJoin.addresses(), com.fatfreecrm.domain.Address.class),
                "emails", def(AssociationJoin.emails(), com.fatfreecrm.domain.Email.class),
                "comments", def(AssociationJoin.comments(), com.fatfreecrm.domain.Comment.class),
                "tags", def(AssociationJoin.tags(), com.fatfreecrm.domain.Tag.class)
            ),
            SearchableEntities::contactSearch,
            true,
            null
        ));
        register(map, new SearchableEntity(
            Lead.class,
            "Lead",
            DEFAULT_PER_PAGE,
            SortWhitelist.of(Lead.class,
                "created_at DESC", "first_name ASC", "last_name ASC", "company ASC",
                "rating DESC", "created_at DESC", "updated_at DESC"),
            Map.of(
                "campaign", def(AssociationJoin.foreignKey(Campaign.class, "campaign"), Campaign.class),
                "contact", def(AssociationJoin.reverseForeignKey(Contact.class, "lead"), Contact.class),
                "tasks", def(AssociationJoin.tasks(), com.fatfreecrm.domain.Task.class),
                "addresses", def(AssociationJoin.addresses(), com.fatfreecrm.domain.Address.class),
                "emails", def(AssociationJoin.emails(), com.fatfreecrm.domain.Email.class),
                "comments", def(AssociationJoin.comments(), com.fatfreecrm.domain.Comment.class),
                "tags", def(AssociationJoin.tags(), com.fatfreecrm.domain.Tag.class)
            ),
            (root, cb, query) -> cb.or(
                likeEscaped(root, cb, "firstName", query),
                likeEscaped(root, cb, "lastName", query),
                likeEscaped(root, cb, "company", query),
                likeEscaped(root, cb, "email", query)
            ),
            true,
            user -> Map.of("status", leadStatusFacets(user, entityManager, settingRepository, accessPolicy)),
            leadStateFilter()
        ));
        register(map, new SearchableEntity(
            Opportunity.class,
            "Opportunity",
            DEFAULT_PER_PAGE,
            SortWhitelist.of(Opportunity.class,
                "created_at DESC", "name ASC", "amount DESC", "amount*probability DESC",
                "probability DESC", "closes_on ASC", "created_at DESC", "updated_at DESC"),
            Map.of(
                "campaign", def(AssociationJoin.foreignKey(Campaign.class, "campaign"), Campaign.class),
                "account", def(AssociationJoin.joinTable(
                    com.fatfreecrm.domain.AccountOpportunity.class, "opportunity", "account"), Account.class),
                "contacts", def(AssociationJoin.contactOpportunities("opportunity", "contact"), Contact.class),
                "emails", def(AssociationJoin.emails(), com.fatfreecrm.domain.Email.class),
                "comments", def(AssociationJoin.comments(), com.fatfreecrm.domain.Comment.class),
                "tags", def(AssociationJoin.tags(), com.fatfreecrm.domain.Tag.class)
            ),
            SearchableEntities::opportunitySearch,
            true,
            null
        ));
        register(map, new SearchableEntity(
            Campaign.class,
            "Campaign",
            DEFAULT_PER_PAGE,
            SortWhitelist.of(Campaign.class,
                "created_at DESC", "name ASC", "target_leads DESC", "target_revenue DESC",
                "leads_count DESC", "revenue DESC", "starts_on DESC", "ends_on DESC",
                "created_at DESC", "updated_at DESC"),
            Map.of(
                "tasks", def(AssociationJoin.tasks(), com.fatfreecrm.domain.Task.class),
                "leads", def(AssociationJoin.reverseForeignKey(Lead.class, "campaign"), Lead.class),
                "opportunities", def(AssociationJoin.reverseForeignKey(Opportunity.class, "campaign"),
                    Opportunity.class),
                "emails", def(AssociationJoin.emails(), com.fatfreecrm.domain.Email.class),
                "comments", def(AssociationJoin.comments(), com.fatfreecrm.domain.Comment.class),
                "tags", def(AssociationJoin.tags(), com.fatfreecrm.domain.Tag.class)
            ),
            (root, cb, query) -> likeEscaped(root, cb, "name", query),
            true,
            null
        ));
        byClass = Map.copyOf(map);
    }

    private static void register(Map<Class<?>, SearchableEntity> map, SearchableEntity entity) {
        map.put(entity.entityClass(), entity);
    }

    private static SearchableEntity.AssociationDef def(AssociationJoin join, Class<?> target) {
        return new SearchableEntity.AssociationDef(join, target);
    }

    private static StateFilter accountStateFilter() {
        return new StateFilter("category", (root, builder, values) -> {
            List<String> categories = new ArrayList<>(values);
            boolean other = categories.removeIf("other"::equals);
            List<Predicate> predicates = new ArrayList<>();
            if (!categories.isEmpty()) {
                predicates.add(root.get("category").in(categories));
            } else if (!other) {
                predicates.add(builder.disjunction());
            }
            if (other) {
                predicates.add(builder.isNull(root.get("category")));
            }
            return builder.or(predicates.toArray(Predicate[]::new));
        });
    }

    private static StateFilter leadStateFilter() {
        return new StateFilter("status", (root, builder, values) -> {
            List<String> statuses = new ArrayList<>(values);
            boolean other = statuses.removeIf("other"::equals);
            List<Predicate> predicates = new ArrayList<>();
            if (!statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            } else if (!other) {
                predicates.add(builder.disjunction());
            }
            if (other) {
                predicates.add(builder.isNull(root.get("status")));
            }
            return builder.or(predicates.toArray(Predicate[]::new));
        });
    }

    public SearchableEntity find(Class<?> entityClass) {
        return byClass.get(entityClass);
    }

    public SearchableEntity forClass(Class<?> entityClass) {
        SearchableEntity entity = byClass.get(entityClass);
        if (entity == null) {
            throw new IllegalArgumentException("Entity type is not searchable: " + entityClass.getName());
        }
        return entity;
    }

    /**
     * Rails {@code AccountsController#get_data_for_sidebar}: per-category counts over the
     * accessible scope only (never the search filters), in configured category order.
     */
    private static Map<String, Long> accountCategoryFacets(
        AuthenticatedUser user,
        EntityManager entityManager,
        SettingRepository settingRepository,
        AccessPolicy accessPolicy
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);
        Root<Account> root = query.from(Account.class);
        Expression<String> category = root.get("category");
        query.multiselect(List.of(category, cb.count(root.get("id"))).toArray(Selection[]::new));
        query.groupBy(category);
        Predicate accessible = accessPolicy.accessibleBy(user, Account.class).toPredicate(root, query, cb);
        if (accessible != null) {
            query.where(accessible);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : entityManager.createQuery(query).getResultList()) {
            Long count = (Long) row[1];
            if (row[0] != null) {
                counts.merge((String) row[0], count, Long::sum);
            }
            total += count;
        }

        Map<String, Long> facets = new LinkedHashMap<>();
        long categorized = 0;
        for (String key : accountCategories(settingRepository)) {
            long count = counts.getOrDefault(key, 0L);
            facets.put(key, count);
            categorized += count;
        }
        facets.put("all", total);
        facets.put("other", total - categorized);
        return facets;
    }

    private static List<String> accountCategories(SettingRepository settingRepository) {
        return settingRepository.findByName("account_category")
            .map(Setting::getValue)
            .map(value -> {
                try {
                    List<String> categories = RailsYaml.readStringList(value).stream()
                        .map(category -> category.startsWith(":") ? category.substring(1) : category)
                        .toList();
                    return categories.isEmpty() ? DEFAULT_ACCOUNT_CATEGORIES : categories;
                } catch (IllegalArgumentException exception) {
                    return DEFAULT_ACCOUNT_CATEGORIES;
                }
            })
            .orElse(DEFAULT_ACCOUNT_CATEGORIES);
    }

    private static Map<String, Long> leadStatusFacets(
        AuthenticatedUser user,
        EntityManager entityManager,
        SettingRepository settingRepository,
        AccessPolicy accessPolicy
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);
        Root<Lead> root = query.from(Lead.class);
        Expression<String> status = root.get("status");
        query.multiselect(List.of(status, cb.count(root.get("id"))).toArray(Selection[]::new));
        query.groupBy(status);
        Predicate accessible = accessPolicy.accessibleBy(user, Lead.class).toPredicate(root, query, cb);
        if (accessible != null) {
            query.where(accessible);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : entityManager.createQuery(query).getResultList()) {
            Long count = (Long) row[1];
            if (row[0] != null) {
                counts.merge((String) row[0], count, Long::sum);
            }
            total += count;
        }

        Map<String, Long> facets = new LinkedHashMap<>();
        long categorized = 0;
        for (String key : leadStatuses(settingRepository)) {
            long count = counts.getOrDefault(key, 0L);
            facets.put(key, count);
            categorized += count;
        }
        facets.put("all", total);
        facets.put("other", total - categorized);
        return facets;
    }

    private static List<String> leadStatuses(SettingRepository settingRepository) {
        return settingRepository.findByName("lead_status")
            .map(Setting::getValue)
            .map(value -> {
                try {
                    List<String> statuses = RailsYaml.readStringList(value).stream()
                        .map(status -> status.startsWith(":") ? status.substring(1) : status)
                        .toList();
                    return statuses.isEmpty() ? DEFAULT_LEAD_STATUSES : statuses;
                } catch (IllegalArgumentException exception) {
                    return DEFAULT_LEAD_STATUSES;
                }
            })
            .orElse(DEFAULT_LEAD_STATUSES);
    }

    /** {@code lower(path) LIKE '%q%' ESCAPE '\\'} with %, _, \ escaped — the Ransack {@code cont} semantics. */
    static Predicate likeEscaped(From<?, ?> root, CriteriaBuilder cb, String attribute, String rawValue) {
        String pattern = "%" + escapeLike(rawValue) + "%";
        return cb.like(cb.lower(root.get(attribute)), pattern.toLowerCase(java.util.Locale.ROOT), '\\');
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Rails {@code Contact.text_search}: first/last name permutations plus contact channels, no escaping. */
    private static Predicate contactSearch(From<?, ?> root, CriteriaBuilder cb, String query) {
        List<Predicate> namePredicates = new ArrayList<>();
        if (query.contains(" ")) {
            for (String[] permutation : namePermutations(query)) {
                namePredicates.add(cb.and(
                    rawMatch(root, cb, "firstName", permutation[0]),
                    rawMatch(root, cb, "lastName", permutation[1])
                ));
            }
        } else {
            namePredicates.add(cb.or(
                rawMatch(root, cb, "firstName", query),
                rawMatch(root, cb, "lastName", query)
            ));
        }
        Predicate other = cb.or(
            rawMatch(root, cb, "email", query),
            rawMatch(root, cb, "altEmail", query),
            rawMatch(root, cb, "phone", query),
            rawMatch(root, cb, "mobile", query)
        );
        Predicate names = namePredicates.size() == 1
            ? namePredicates.get(0)
            : cb.or(namePredicates.toArray(Predicate[]::new));
        return cb.or(names, other);
    }

    /** {@code String#name_permutations}: every first/last split point, both orders. */
    static List<String[]> namePermutations(String query) {
        String[] parts = query.split(" ");
        List<String[]> result = new ArrayList<>();
        for (int index = 0; index < parts.length - 1; index++) {
            String first = String.join(" ", java.util.Arrays.copyOfRange(parts, 0, index + 1));
            String last = String.join(" ", java.util.Arrays.copyOfRange(parts, index + 1, parts.length));
            result.add(new String[] {first, last});
            result.add(new String[] {last, first});
        }
        return result;
    }

    /** {@code lower(path) LIKE '%q%'} with the raw pattern — Rails {@code matches} semantics without escaping. */
    private static Predicate rawMatch(From<?, ?> root, CriteriaBuilder cb, String attribute, String rawValue) {
        return cb.like(cb.lower(root.get(attribute)), "%" + rawValue.toLowerCase(java.util.Locale.ROOT) + "%");
    }

    /** Rails {@code Opportunity.text_search}: numeric queries also match the row id. */
    private static Predicate opportunitySearch(From<?, ?> root, CriteriaBuilder cb, String query) {
        if (query.matches("\\d+")) {
            Expression<String> upper = cb.upper(root.get("name"));
            Predicate nameMatch = cb.like(upper, "%" + query.toUpperCase(java.util.Locale.ROOT) + "%");
            try {
                return cb.or(nameMatch, cb.equal(root.get("id"), Long.valueOf(query)));
            } catch (NumberFormatException overflow) {
                return nameMatch;
            }
        }
        return likeEscaped(root, cb, "name", query);
    }
}
