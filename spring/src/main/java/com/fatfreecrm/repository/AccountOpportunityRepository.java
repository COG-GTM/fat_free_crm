package com.fatfreecrm.repository;

import com.fatfreecrm.domain.AccountOpportunity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountOpportunityRepository extends JpaRepository<AccountOpportunity, Long> {

    @Query("SELECT accountOpportunity.opportunity.id FROM AccountOpportunity accountOpportunity "
        + "WHERE accountOpportunity.account.id = :accountId")
    List<Long> findOpportunityIdsByAccountId(@Param("accountId") Long accountId);

    @Query("SELECT link FROM AccountOpportunity link WHERE link.account.id = :accountId")
    List<AccountOpportunity> findByAccountId(@Param("accountId") Long accountId);

    @Query("SELECT link FROM AccountOpportunity link WHERE link.opportunity.id = :opportunityId")
    List<AccountOpportunity> findByOpportunityId(@Param("opportunityId") Long opportunityId);
}
