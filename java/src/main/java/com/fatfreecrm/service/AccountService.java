package com.fatfreecrm.service;

import static com.fatfreecrm.repository.AccountSpecifications.idEquals;
import static com.fatfreecrm.repository.AccountSpecifications.textSearch;
import static com.fatfreecrm.security.AccessControlSpecifications.visibleTo;

import com.fatfreecrm.api.dto.AccountDto;
import com.fatfreecrm.api.dto.AccountMapper;
import com.fatfreecrm.api.dto.AutoCompleteResponse;
import com.fatfreecrm.api.dto.PageResponse;
import com.fatfreecrm.config.PaginationProperties;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.security.CurrentUser;
import com.fatfreecrm.security.CurrentUserProvider;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only account queries (Rails {@code AccountsController#index/#show/#auto_complete}).
 * Every query is scoped to the rows the current user may see
 * ({@code AccessControlSpecifications.visibleTo}, Rails {@code Account.my(current_user)}); the
 * {@code @SQLRestriction} on {@link Account} hides soft-deleted rows.
 */
@Service
@Transactional(readOnly = true)
public class AccountService {

    /** Rails {@code ApplicationController#auto_complete}: {@code .limit(10)}. */
    static final int AUTOCOMPLETE_LIMIT = 10;

    private static final String ASSET_TYPE = "Account";

    private final AccountRepository repository;
    private final AccountMapper mapper;
    private final CurrentUserProvider currentUserProvider;
    private final PaginationProperties pagination;

    public AccountService(
            AccountRepository repository,
            AccountMapper mapper,
            CurrentUserProvider currentUserProvider,
            PaginationProperties pagination) {
        this.repository = repository;
        this.mapper = mapper;
        this.currentUserProvider = currentUserProvider;
        this.pagination = pagination;
    }

    /**
     * @param page    1-based page; {@code < 1} → 1
     * @param perPage page size; {@code null} → default, otherwise clamped to {@code 1..max}
     *                ({@link PaginationProperties#clampPageSize})
     * @param query   Rails {@code text_search}: substring of {@code name} or {@code email},
     *                case-insensitive; blank → no filter
     * @param sortBy  one of the Rails {@code sortable by:} values (see {@link AccountSort}),
     *                blank → {@code created_at DESC}
     * @throws IllegalArgumentException when {@code sortBy} is not a known order; the controller
     *         validates the parameter with {@link AccountSort#PATTERN} first so this surfaces
     *         as a 400 before reaching the service
     */
    public PageResponse<AccountDto> list(int page, Integer perPage, String query, String sortBy) {
        CurrentUser user = currentUserProvider.requireCurrentUser();
        int pageNumber = Math.max(1, page);
        int pageSize = pagination.clampPageSize(perPage);
        Sort sort = AccountSort.parse(sortBy).toSort();

        Specification<Account> spec = visible(user).and(textSearch(query));
        Page<Account> result = repository.findAll(spec, PageRequest.of(pageNumber - 1, pageSize, sort));
        return new PageResponse<>(mapper.toDtos(result.getContent()), pageNumber, pageSize, result.getTotalElements());
    }

    /**
     * @throws ResourceNotFoundException when no such account exists, it is soft-deleted, or the
     *         current user may not see it. Rails answers 404 in all three cases too
     *         ({@code load_and_authorize_resource} loads through {@code Account.my(current_user)}
     *         and {@code respond_to_not_found} renders the miss), so the API never reveals whether
     *         an invisible id exists.
     */
    public AccountDto get(long id) {
        CurrentUser user = currentUserProvider.requireCurrentUser();
        return repository.findOne(visible(user).and(idEquals(id)))
                .map(mapper::toDto)
                .orElseThrow(() -> new ResourceNotFoundException(ASSET_TYPE, id));
    }

    /**
     * Rails {@code auto_complete}: visible rows matching {@code term} via {@code text_search},
     * first {@value #AUTOCOMPLETE_LIMIT} by {@code name ASC}; {@code text} is {@code name}. The
     * Rails {@code related} exclusion parameter is deferred and not applied.
     */
    public AutoCompleteResponse autocomplete(String term) {
        CurrentUser user = currentUserProvider.requireCurrentUser();
        Specification<Account> spec = visible(user).and(textSearch(term));
        List<Account> matches = repository.findBy(spec,
                q -> q.sortBy(Sort.by("name", "id").ascending()).limit(AUTOCOMPLETE_LIMIT).all());
        return new AutoCompleteResponse(matches.stream()
                .map(a -> new AutoCompleteResponse.Item(a.getId(), a.getName()))
                .toList());
    }

    private static Specification<Account> visible(CurrentUser user) {
        return visibleTo(user, ASSET_TYPE);
    }
}
