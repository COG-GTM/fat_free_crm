package com.fatfreecrm.service.read;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.authz.AccessPolicy;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResource;
import com.fatfreecrm.service.query.CrmQueryService;
import com.fatfreecrm.service.query.ListQuery;
import com.fatfreecrm.service.query.ListResult;
import com.fatfreecrm.service.query.SearchableEntities;
import com.fatfreecrm.service.query.SearchableEntity;
import com.fatfreecrm.service.query.RubyScalars;
import jakarta.persistence.criteria.Predicate;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.support.Repositories;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CrmReadService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrmReadService.class);

    private final CrmQueryService crmQueryService;
    private final RailsJsonWriter jsonWriter;
    private final RecentlyViewedService recentlyViewedService;
    private final AccessPolicy accessPolicy;
    private final SearchableEntities searchableEntities;
    private final Repositories repositories;

    public CrmReadService(
        ApplicationContext applicationContext,
        CrmQueryService crmQueryService,
        RailsJsonWriter jsonWriter,
        RecentlyViewedService recentlyViewedService,
        AccessPolicy accessPolicy,
        SearchableEntities searchableEntities
    ) {
        this.repositories = new Repositories(applicationContext);
        this.crmQueryService = crmQueryService;
        this.jsonWriter = jsonWriter;
        this.recentlyViewedService = recentlyViewedService;
        this.accessPolicy = accessPolicy;
        this.searchableEntities = searchableEntities;
    }

    @Transactional(readOnly = true)
    public ListResult<ObjectNode> list(AuthenticatedUser user, RailsResource resource, ListQuery query) {
        ListResult<?> result = crmQueryService.list(user, resource.entityClass(), query);
        List<Long> ids = result.items().stream()
            .map(item -> ((com.fatfreecrm.domain.support.BaseEntity) item).getId())
            .toList();
        return new ListResult<>(jsonWriter.write(resource, ids), result.page(), result.perPage(),
            result.totalCount(), result.totalPages(), result.facets());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ObjectNode show(AuthenticatedUser user, RailsResource resource, long id) {
        ObjectNode response = jsonWriter.writeOne(resource, id);
        try {
            recentlyViewedService.recordView(user, resource, id);
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to record recently viewed {} {}", resource.railsModel(), id, exception);
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    public AutocompleteResult autocomplete(
        AuthenticatedUser user,
        RailsResource resource,
        String term,
        String related
    ) {
        SearchableEntity entity = searchableEntities.forClass(resource.entityClass());
        Specification<Object> specification =
            accessPolicy.accessibleBy(user, (Class<Object>) resource.entityClass());
        if (term != null && !term.isBlank()) {
            specification = specification.and((root, query, builder) ->
                entity.textSearch().search(root, builder, term));
        }
        Set<Long> excluded = excludedIds(resource, related);
        if (!excluded.isEmpty()) {
            specification = specification.and((root, query, builder) -> {
                Predicate excludedIds = root.get("id").in(excluded);
                return builder.not(excludedIds);
            });
        }
        JpaSpecificationExecutor<Object> repository = (JpaSpecificationExecutor<Object>) repositories
            .getRepositoryFor(resource.entityClass())
            .filter(JpaSpecificationExecutor.class::isInstance)
            .orElseThrow(() -> new IllegalArgumentException(
                "No repository for " + resource.entityClass().getName()));
        List<AutocompleteResult.Item> items = repository.findAll(
                specification, PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "id")))
            .stream()
            .map(item -> new AutocompleteResult.Item(
                ((com.fatfreecrm.domain.support.BaseEntity) item).getId(),
                resource.autocompleteText().apply(item)))
            .toList();
        return new AutocompleteResult(items);
    }

    private Set<Long> excludedIds(RailsResource resource, String related) {
        if (related == null || related.isBlank()) {
            return Set.of();
        }
        String[] segments = related.split("/", -1);
        if (segments.length == 1) {
            return Set.of(RubyScalars.toLong(segments[0]));
        }
        if (segments.length != 2) {
            return Set.of();
        }
        RailsResource.RelatedExclusion exclusion = resource.relatedExclusions().get(segments[0]);
        if (exclusion == null) {
            return Set.of();
        }
        return exclusion.ids().apply(RubyScalars.toLong(segments[1]));
    }
}
