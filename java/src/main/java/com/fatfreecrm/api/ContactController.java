package com.fatfreecrm.api;

import com.fatfreecrm.api.dto.AutoCompleteResponse;
import com.fatfreecrm.api.dto.ContactDto;
import com.fatfreecrm.api.dto.PageResponse;
import com.fatfreecrm.config.PaginationProperties;
import com.fatfreecrm.service.ContactService;
import com.fatfreecrm.service.ContactSort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only contact endpoints (Rails {@code ContactsController#index/#show/#auto_complete}
 * under the {@code /api/v1} prefix). HTTP concerns only — parameter parsing, aliases and
 * validation; everything else is {@link ContactService}.
 *
 * <p>Query parameters accept both the camelCase names used by this API and the Rails snake_case
 * names ({@code per_page}, {@code sort_by}); the camelCase spelling wins when both are sent.
 * Validation failures (page &lt; 1, perPage outside 1..200, unknown sortBy) are 400
 * {@code application/problem+json} via {@link ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/contacts")
@Validated
public class ContactController {

    private static final String SORT_BY_MESSAGE = "must be one of: first_name ASC, last_name ASC, created_at DESC, "
            + "updated_at DESC (or first_name, last_name, created_at, updated_at)";

    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @GetMapping
    public PageResponse<ContactDto> list(
            @RequestParam(name = "page", required = false) @Min(1) Integer page,
            @RequestParam(name = "perPage", required = false)
            @Min(1) @Max(PaginationProperties.MAX_PAGE_SIZE) Integer perPage,
            @RequestParam(name = "per_page", required = false)
            @Min(1) @Max(PaginationProperties.MAX_PAGE_SIZE) Integer perPageAlias,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "sortBy", required = false)
            @Pattern(regexp = ContactSort.PATTERN, message = SORT_BY_MESSAGE) String sortBy,
            @RequestParam(name = "sort_by", required = false)
            @Pattern(regexp = ContactSort.PATTERN, message = SORT_BY_MESSAGE) String sortByAlias) {
        int effectivePage = page == null ? 1 : page;
        int effectivePerPage = firstNonNull(perPage, perPageAlias, PaginationProperties.DEFAULT_PAGE_SIZE);
        String effectiveSortBy = firstNonNull(sortBy, sortByAlias, null);
        return contactService.list(effectivePage, effectivePerPage, query, effectiveSortBy);
    }

    @GetMapping("/{id:\\d+}")
    public ContactDto get(@PathVariable long id) {
        return contactService.get(id);
    }

    @GetMapping("/autocomplete")
    public AutoCompleteResponse autocomplete(@RequestParam(name = "term", required = false) String term) {
        return contactService.autocomplete(term);
    }

    private static <T> T firstNonNull(T preferred, T alias, T fallback) {
        if (preferred != null) {
            return preferred;
        }
        return alias != null ? alias : fallback;
    }
}
