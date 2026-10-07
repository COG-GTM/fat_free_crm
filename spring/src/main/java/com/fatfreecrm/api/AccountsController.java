package com.fatfreecrm.api;

import com.fatfreecrm.api.dto.AccountResponse;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.domain.Tagging;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.TaggingRepository;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import com.fatfreecrm.service.query.CrmQueryService;
import com.fatfreecrm.service.query.ListQuery;
import com.fatfreecrm.service.query.ListResult;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// throwaway: replaced by AB-270
@Hidden
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountsController {

    private final CrmQueryService crmQueryService;
    private final TaggingRepository taggingRepository;

    public AccountsController(CrmQueryService crmQueryService, TaggingRepository taggingRepository) {
        this.crmQueryService = crmQueryService;
        this.taggingRepository = taggingRepository;
    }

    @GetMapping
    public ListResult<AccountResponse> index(
        Authentication authentication,
        @RequestParam MultiValueMap<String, String> params
    ) {
        ListQuery query = ListQuery.fromParameters(params);
        ListResult<Account> result = crmQueryService.list(
            ((FfcrmAuthenticationToken) authentication).getAuthenticatedUser(), Account.class, query);
        Map<Long, List<String>> tagsByAccount = tagLists(result.items());
        return result.map(account -> AccountResponse.from(
            account, tagsByAccount.getOrDefault(account.getId(), List.of())));
    }

    /** Batch-loads tag names for the page in one query, ordered by tagging id (no N+1). */
    private Map<Long, List<String>> tagLists(List<Account> accounts) {
        Map<Long, List<String>> byAccount = new LinkedHashMap<>();
        if (accounts.isEmpty()) {
            return byAccount;
        }
        List<Integer> ids = accounts.stream().map(account -> account.getId().intValue()).toList();
        for (Tagging tagging : taggingRepository.findByTaggableTypeAndContextAndTaggableIdInOrderById(
            RailsModelType.ACCOUNT.railsName(), "tags", ids)) {
            Tag tag = tagging.getTag();
            if (tag != null) {
                byAccount.computeIfAbsent(tagging.getTaggableId().longValue(), key -> new ArrayList<>())
                    .add(tag.getName());
            }
        }
        return byAccount;
    }
}
