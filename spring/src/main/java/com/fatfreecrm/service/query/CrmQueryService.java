package com.fatfreecrm.service.query;

import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.authz.AccessPolicy;
import com.fatfreecrm.service.query.RansackParser.SearchPlan;
import com.fatfreecrm.service.query.RansackParser.SortEntry;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.support.Repositories;
import org.springframework.stereotype.Service;

/**
 * The single list-query path: {@code accessPolicy.accessibleBy(user, type).and(search)}.
 * Mirrors Rails {@code EntitiesController#get_list_of_records}: access scope, Ransack
 * advanced search, {@code query} text search, {@code #tag} filters, whitelist ordering and
 * WillPaginate-style pagination.
 */
@Service
public class CrmQueryService {

    private static final int MAX_PER_PAGE = 200;

    private final Repositories repositories;
    private final AccessPolicy accessPolicy;
    private final SearchableEntities searchableEntities;
    private final RansackParser ransackParser;

    public CrmQueryService(
        ApplicationContext applicationContext,
        AccessPolicy accessPolicy,
        SearchableEntities searchableEntities,
        RansackParser ransackParser
    ) {
        this.repositories = new Repositories(applicationContext);
        this.accessPolicy = accessPolicy;
        this.searchableEntities = searchableEntities;
        this.ransackParser = ransackParser;
    }

    @SuppressWarnings("unchecked")
    public <T> ListResult<T> list(AuthenticatedUser user, Class<T> type, ListQuery query) {
        SearchableEntity entity = searchableEntities.forClass(type);
        JpaSpecificationExecutor<T> repository = (JpaSpecificationExecutor<T>) repositories
            .getRepositoryFor(type)
            .filter(JpaSpecificationExecutor.class::isInstance)
            .orElseThrow(() -> new IllegalArgumentException("No repository for " + type.getName()));

        int page = parsePage(query.page());
        int perPage = parsePerPage(query, entity);

        SearchPlan<T> plan = ransackParser.parse(type, query.q());
        SearchText searchText = SearchText.parse(query.query(), entity.taggable());

        Specification<T> search = searchSpec(entity, plan, searchText, sortPlanFor(type, entity, query, plan));
        if (!plan.advanced() && entity.stateFilter() != null && query.filter() != null) {
            List<String> values = entity.stateFilter().values(query.filter());
            if (!values.isEmpty()) {
                search = search.and((root, criteria, builder) ->
                    entity.stateFilter().predicate().create(root, builder, values));
            }
        }
        Specification<T> spec = accessPolicy.accessibleBy(user, type).and(search);

        long offset = (long) (page - 1) * perPage;
        long total;
        List<T> items;
        if (offset > Integer.MAX_VALUE) {
            total = repository.count(spec);
            items = List.of();
        } else {
            Page<T> result = repository.findAll(spec, PageRequest.of(page - 1, perPage, Sort.unsorted()));
            total = result.getTotalElements();
            items = result.getContent();
        }
        int totalPages = perPage == 0 ? 0 : (int) ((total + perPage - 1) / perPage);

        Map<String, Map<String, Long>> facets = entity.facets() == null
            ? Map.of()
            : entity.facets().facets(user);
        return new ListResult<>(items, page, perPage, total, totalPages, facets);
    }

    /** Rails {@code params[:page].to_i}: absent is page 1, anything below 1 is a 404. */
    static int parsePage(String raw) {
        if (raw == null) {
            return 1;
        }
        long page = RubyScalars.toLong(raw);
        if (page < 1) {
            throw new InvalidPageException("Invalid page parameter: " + raw);
        }
        return page > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) page;
    }

    /** Rails {@code per_page_param}: clamp to 1..200; absent falls back to preference then default. */
    static int parsePerPage(ListQuery query, SearchableEntity entity) {
        if (query.perPage() == null) {
            return query.preferredPerPage() != null ? query.preferredPerPage() : entity.defaultPerPage();
        }
        long parsed = RubyScalars.toLong(query.perPage());
        return (int) Math.max(1, Math.min(parsed, MAX_PER_PAGE));
    }

    /**
     * Builds the {@code and(...)}-side specification: Ransack tree, text search, tag EXISTS
     * filters, and ordering (applied only for entity queries, never for count queries).
     */
    private <T> Specification<T> searchSpec(
        SearchableEntity entity,
        SearchPlan<T> plan,
        SearchText searchText,
        SortPlan sortPlan
    ) {
        return (root, cq, cb) -> {
            if (cq.getResultType() != Long.class && cq.getResultType() != long.class) {
                cq.orderBy(sortPlan.orders(root, cb));
            }
            List<Predicate> predicates = new ArrayList<>();
            if (plan.where() != null) {
                Predicate predicate = plan.where().toPredicate(root, cq, cb);
                if (predicate != null) {
                    predicates.add(predicate);
                }
            }
            if (searchText.text() != null) {
                predicates.add(entity.textSearch().search(root, cb, searchText.text()));
            }
            if (searchText.tagFilterRequested()) {
                if (searchText.tags().isEmpty()) {
                    return cb.disjunction();
                }
                for (String tag : searchText.tags()) {
                    predicates.add(searchText.tagPredicate(root, cq, cb, entity.railsName(), tag));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Resolves ordering. Advanced search uses only the valid {@code q[s]} entries (root
     * ransackable attributes); everything else falls back to the entity whitelist default.
     * {@code id ASC} is always appended as the final tiebreaker for deterministic pages.
     */
    private <T> SortPlan sortPlanFor(Class<T> type, SearchableEntity entity, ListQuery query, SearchPlan<T> plan) {
        if (plan.advanced()) {
            return (root, cb) -> {
                List<jakarta.persistence.criteria.Order> orders = new ArrayList<>();
                for (SortEntry entry : plan.sorts()) {
                    jakarta.persistence.criteria.Expression<?> path = entry.foreignKey()
                        ? root.get(entry.field()).get("id")
                        : root.get(entry.field());
                    orders.add(entry.descending() ? cb.desc(path) : cb.asc(path));
                }
                orders.add(cb.asc(root.get("id")));
                return orders;
            };
        }
        String selector = query.sortBy() != null ? query.sortBy() : query.preferredSortBy();
        return (root, cb) -> {
            List<jakarta.persistence.criteria.Order> orders = new ArrayList<>(
                entity.sortWhitelist().resolveOrder(selector, root, cb));
            orders.add(cb.asc(root.get("id")));
            return orders;
        };
    }

    /** Lazily resolves criteria {@link jakarta.persistence.criteria.Order}s for a query. */
    private interface SortPlan {
        List<jakarta.persistence.criteria.Order> orders(Root<?> root, CriteriaBuilder cb);
    }
}
