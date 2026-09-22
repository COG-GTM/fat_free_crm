package com.fatfreecrm.api;

import com.fatfreecrm.api.dto.AccountDto;
import com.fatfreecrm.api.dto.AutoCompleteResponse;
import com.fatfreecrm.api.dto.PageResponse;
import com.fatfreecrm.service.AccountService;
import com.fatfreecrm.service.AccountSort;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
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
 * spellings {@code per_page} and {@code sort_by} are accepted as aliases. When both spellings are
 * sent the camelCase one wins and the alias is ignored entirely, so the aliased parameters are
 * bound as raw strings and only the selected value is validated ({@link ListParams}). Violations
 * are rendered as 400 problem+json by {@link ApiExceptionHandler}; {@code perPage} above the
 * maximum is clamped, not rejected.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Validated
public class AccountController {

    private final AccountService accountService;
    private final Validator validator;

    public AccountController(AccountService accountService, Validator validator) {
        this.accountService = accountService;
        this.validator = validator;
    }

    @GetMapping
    public PageResponse<AccountDto> list(
            @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(name = "perPage", required = false) String perPage,
            @RequestParam(name = "per_page", required = false) String perPageAlias,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "sortBy", required = false) String sortBy,
            @RequestParam(name = "sort_by", required = false) String sortByAlias) {
        ListParams params = validate(new ListParams(
                firstNonNull(perPage, perPageAlias),
                firstNonNull(sortBy, sortByAlias)));
        return accountService.list(page, params.perPageValue(), query, params.sortBy());
    }

    @GetMapping("/{id:\\d+}")
    public AccountDto get(@PathVariable long id) {
        return accountService.get(id);
    }

    @GetMapping("/autocomplete")
    public AutoCompleteResponse autocomplete(@RequestParam(name = "term", required = false) String term) {
        return accountService.autocomplete(term);
    }

    /** The alias-resolved list parameters; validated as a whole once the preferred spelling has won. */
    record ListParams(
            @Pattern(regexp = "\\s*[1-9]\\d{0,8}\\s*", message = "must be a positive integer") String perPage,
            @Pattern(regexp = AccountSort.PATTERN, message = AccountSort.PATTERN_MESSAGE) String sortBy) {

        Integer perPageValue() {
            return perPage == null ? null : Integer.valueOf(perPage.strip());
        }
    }

    private <T> T validate(T value) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return value;
    }

    private static <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
