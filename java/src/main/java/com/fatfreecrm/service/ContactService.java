package com.fatfreecrm.service;

import static com.fatfreecrm.repository.ContactSpecifications.idEquals;
import static com.fatfreecrm.repository.ContactSpecifications.textSearch;
import static com.fatfreecrm.security.AccessControlSpecifications.visibleTo;

import com.fatfreecrm.api.dto.AutoCompleteResponse;
import com.fatfreecrm.api.dto.ContactDto;
import com.fatfreecrm.api.dto.ContactMapper;
import com.fatfreecrm.api.dto.PageResponse;
import com.fatfreecrm.config.PaginationProperties;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.repository.ContactRepository;
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
 * Read-only contact use cases (Rails {@code ContactsController#index/#show/#auto_complete}).
 * Every query is scoped with {@code AccessControlSpecifications.visibleTo(currentUser, "Contact")}
 * so callers only ever see what {@code Contact.my(current_user)} would return.
 */
@Service
@Transactional(readOnly = true)
public class ContactService {

    /** Rails class name stored in {@code permissions.asset_type}. */
    static final String ASSET_TYPE = "Contact";

    /** Rails {@code auto_complete} hard-codes {@code limit(10)}. */
    static final int AUTOCOMPLETE_LIMIT = 10;

    private static final Sort AUTOCOMPLETE_SORT = Sort.by(Sort.Order.asc("firstName"), Sort.Order.asc("lastName"));

    private final ContactRepository repository;
    private final ContactMapper mapper;
    private final CurrentUserProvider currentUserProvider;
    private final PaginationProperties pagination;

    public ContactService(
            ContactRepository repository,
            ContactMapper mapper,
            CurrentUserProvider currentUserProvider,
            PaginationProperties pagination) {
        this.repository = repository;
        this.mapper = mapper;
        this.currentUserProvider = currentUserProvider;
        this.pagination = pagination;
    }

    /**
     * @param page    1-based page number; values below 1 are treated as 1
     * @param perPage requested page size, clamped to {@code 1..maxPageSize} (the controller
     *                already rejects out-of-range values with 400; this is a safety net)
     * @param query   Rails {@code text_search} query; blank means no filtering
     * @param sortBy  one of {@link ContactSort}'s accepted values; blank means the default
     * @throws IllegalArgumentException for an unknown {@code sortBy}
     */
    public PageResponse<ContactDto> list(int page, int perPage, String query, String sortBy) {
        CurrentUser user = currentUserProvider.requireCurrentUser();
        int effectivePage = Math.max(1, page);
        int effectivePerPage = pagination.clampPageSize(perPage);
        Sort sort = ContactSort.parse(sortBy).sort();

        Specification<Contact> spec = visibleTo(user, ASSET_TYPE);
        spec = spec.and(textSearch(query));
        Page<Contact> result = repository.findAll(spec, PageRequest.of(effectivePage - 1, effectivePerPage, sort));

        List<ContactDto> items = result.getContent().stream().map(mapper::toDto).toList();
        return new PageResponse<>(items, effectivePage, effectivePerPage, result.getTotalElements());
    }

    /**
     * @throws ResourceNotFoundException when no contact with {@code id} exists, it is
     *         soft-deleted, <em>or</em> it is not visible to the current user. Rails answers
     *         404 in all three cases ({@code load_and_authorize_resource} loads through
     *         {@code Contact.my(current_user)}, so an invisible record is simply not found),
     *         and deliberately does not distinguish "does not exist" from "may not see" to
     *         avoid leaking record existence.
     */
    public ContactDto get(long id) {
        CurrentUser user = currentUserProvider.requireCurrentUser();
        Specification<Contact> spec = visibleTo(user, ASSET_TYPE);
        return repository.findOne(spec.and(idEquals(id)))
                .map(mapper::toDto)
                .orElseThrow(() -> new ResourceNotFoundException(ASSET_TYPE, id));
    }

    /**
     * Rails {@code auto_complete}: visible contacts matching {@code term} via
     * {@code text_search}, at most {@link #AUTOCOMPLETE_LIMIT}, {@code text} being
     * {@code full_name} ({@code first_name + " " + last_name}). Ordered by first then last name
     * for a stable result (Rails leaves the order to the database). The Rails {@code related}
     * exclusion parameter is deferred to a later phase and not accepted here.
     */
    public AutoCompleteResponse autocomplete(String term) {
        CurrentUser user = currentUserProvider.requireCurrentUser();
        Specification<Contact> spec = visibleTo(user, ASSET_TYPE);
        spec = spec.and(textSearch(term));
        List<AutoCompleteResponse.Item> results = repository
                .findAll(spec, PageRequest.of(0, AUTOCOMPLETE_LIMIT, AUTOCOMPLETE_SORT))
                .getContent().stream()
                .map(c -> new AutoCompleteResponse.Item(c.getId(), fullName(c)))
                .toList();
        return new AutoCompleteResponse(results);
    }

    /** Rails {@code Contact#full_name} with the default ("before") format. */
    static String fullName(Contact contact) {
        return contact.getFirstName() + " " + contact.getLastName();
    }
}
