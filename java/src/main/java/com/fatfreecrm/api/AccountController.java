package com.fatfreecrm.api;

import com.fatfreecrm.api.dto.AccountDto;
import com.fatfreecrm.api.dto.AutoCompleteResponse;
import com.fatfreecrm.api.dto.PageResponse;
import com.fatfreecrm.service.AccountService;
import com.fatfreecrm.service.AccountSort;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only account endpoints (Rails {@code AccountsController#index/#show/#auto_complete},
 * mounted under {@code /api/v1}). Parses and validates query parameters, delegates to
 * {@link AccountService}; nothing else.
 *
 * <p>Query parameters are camelCase per target-architecture.md §2.5; the Rails snake_case
 * spellings {@code per_page} and {@code sort_by} are accepted as aliases (camelCase wins when
 * both are sent). Violations of the constraints below are rendered as 400 problem+json by
 * {@link ApiExceptionHandler}; {@code perPage} above the maximum is clamped, not rejected.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Validated
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public PageResponse<AccountDto> list(
            @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(name = "perPage", required = false) @Min(1) Integer perPage,
            @RequestParam(name = "per_page", required = false) @Min(1) Integer perPageAlias,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "sortBy", required = false)
            @Pattern(regexp = AccountSort.PATTERN, message = AccountSort.PATTERN_MESSAGE) String sortBy,
            @RequestParam(name = "sort_by", required = false)
            @Pattern(regexp = AccountSort.PATTERN, message = AccountSort.PATTERN_MESSAGE) String sortByAlias) {
        return accountService.list(
                page,
                firstNonNull(perPage, perPageAlias),
                query,
                firstNonNull(sortBy, sortByAlias));
    }

    @GetMapping("/{id:\\d+}")
    public AccountDto get(@PathVariable long id) {
        return accountService.get(id);
    }

    @GetMapping("/autocomplete")
    public AutoCompleteResponse autocomplete(@RequestParam(name = "term", required = false) String term) {
        return accountService.autocomplete(term);
    }

    private static <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
