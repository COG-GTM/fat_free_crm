package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.AccountOpportunity;
import com.fatfreecrm.domain.Opportunity;
import java.util.List;
import java.util.Optional;

public interface AccountOpportunityRepository extends SoftDeletableRepository<AccountOpportunity> {

    List<AccountOpportunity> findByAccount(Account account);

    Optional<AccountOpportunity> findByOpportunity(Opportunity opportunity);
}
