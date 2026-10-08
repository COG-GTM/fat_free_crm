package com.fatfreecrm.service.read;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.authz.AccessPolicy;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResource;
import com.fatfreecrm.service.query.CrmQueryService;
import com.fatfreecrm.service.query.ListQuery;
import com.fatfreecrm.service.query.ListResult;
import com.fatfreecrm.service.query.SearchableEntities;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this service."
)
public class UsersReadService {

    private final UserRepository userRepository;
    private final AccessPolicy accessPolicy;
    private final SearchableEntities searchableEntities;
    private final CrmQueryService queryService;
    private final RailsJsonWriter jsonWriter;
    private final RailsResource userResource;

    public UsersReadService(
        UserRepository userRepository,
        AccessPolicy accessPolicy,
        SearchableEntities searchableEntities,
        CrmQueryService queryService,
        RailsJsonWriter jsonWriter,
        com.fatfreecrm.service.json.RailsResources resources
    ) {
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.searchableEntities = searchableEntities;
        this.queryService = queryService;
        this.jsonWriter = jsonWriter;
        this.userResource = resources.user;
    }

    @Transactional(readOnly = true)
    public String name(long id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("User " + id + " not found"));
        return name(user);
    }

    @Transactional(readOnly = true)
    public String currentName(AuthenticatedUser authenticatedUser) {
        return name(authenticatedUser.id());
    }

    @Transactional(readOnly = true)
    public AutocompleteResult autocomplete(AuthenticatedUser user, String term) {
        Specification<User> specification = accessPolicy.accessibleBy(user, User.class);
        if (term != null && !term.isBlank()) {
            specification = specification.and((root, query, builder) ->
                searchableEntities.forClass(User.class).textSearch().search(root, builder, term));
        }
        Sort sort = Sort.by(
            Sort.Order.asc("firstName"),
            Sort.Order.asc("lastName"),
            Sort.Order.asc("id")
        );
        List<AutocompleteResult.Item> results = userRepository.findAll(specification, PageRequest.of(0, 10, sort))
            .stream()
            .map(candidate -> new AutocompleteResult.Item(candidate.getId(), escapeJavascript(fullName(candidate)
                + " (@" + candidate.getUsername() + ")")))
            .toList();
        return new AutocompleteResult(results);
    }

    @Transactional(readOnly = true)
    public ListResult<com.fasterxml.jackson.databind.node.ObjectNode> adminList(
        AuthenticatedUser user,
        MultiValueMap<String, String> parameters
    ) {
        ListResult<User> result = queryService.list(
            user, User.class, ListQuery.fromParameters(withDefaultRansackSort(parameters)));
        List<Long> ids = result.items().stream().map(User::getId).toList();
        return new ListResult<>(
            jsonWriter.write(userResource, ids),
            result.page(),
            result.perPage(),
            result.totalCount(),
            result.totalPages(),
            Map.of()
        );
    }

    private static MultiValueMap<String, String> withDefaultRansackSort(
        MultiValueMap<String, String> parameters
    ) {
        boolean hasRansackFilter = parameters.keySet().stream()
            .anyMatch(key -> key.startsWith("q[") && !key.equals("q[s]"));
        List<String> requestedSorts = parameters.get("q[s]");
        boolean hasRequestedSort = requestedSorts != null
            && requestedSorts.stream().anyMatch(value -> value != null && !value.isBlank());
        if (!hasRansackFilter || hasRequestedSort) {
            return parameters;
        }
        LinkedMultiValueMap<String, String> sortedParameters = new LinkedMultiValueMap<>(parameters);
        sortedParameters.set("q[s]", "id desc");
        return sortedParameters;
    }

    private static String name(User user) {
        return blank(user.getFirstName()) ? user.getUsername() : user.getFirstName();
    }

    private static String fullName(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName();
        String last = user.getLastName() == null ? "" : user.getLastName();
        return blank(first) && blank(last) ? user.getEmail() : (first + " " + last).strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String escapeJavascript(String value) {
        Map<String, String> escapeMap = Map.ofEntries(
            Map.entry("\\", "\\\\"),
            Map.entry("</", "<\\/"),
            Map.entry("\r\n", "\\n"),
            Map.entry("\n", "\\n"),
            Map.entry("\r", "\\n"),
            Map.entry("\"", "\\\""),
            Map.entry("'", "\\'"),
            Map.entry("`", "\\`"),
            Map.entry("$", "\\$"),
            Map.entry("\u2028", "&#x2028;"),
            Map.entry("\u2029", "&#x2029;")
        );
        String escaped = value;
        for (String key : List.of("\\", "</", "\r\n", "\n", "\r", "\"", "'", "`", "$", "\u2028", "\u2029")) {
            escaped = escaped.replace(key, escapeMap.get(key));
        }
        return escaped;
    }
}
