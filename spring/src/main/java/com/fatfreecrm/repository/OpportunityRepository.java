package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Opportunity;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OpportunityRepository extends JpaRepository<Opportunity, Long>,
        JpaSpecificationExecutor<Opportunity> {

    @Query("SELECT opportunity.id FROM Opportunity opportunity WHERE opportunity.user.id = :userId")
    List<Long> findIdsByUserId(@Param("userId") Long userId);

    @Query("SELECT opportunity.id FROM Opportunity opportunity WHERE opportunity.campaign.id = :campaignId")
    List<Long> findIdsByCampaignId(@Param("campaignId") Long campaignId);
}
