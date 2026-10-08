package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Lead;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {

    @Query("SELECT lead.id FROM Lead lead WHERE lead.campaign.id = :campaignId")
    List<Long> findIdsByCampaignId(@Param("campaignId") Long campaignId);

    @Query("SELECT lead.id FROM Lead lead WHERE lead.user.id = :userId")
    List<Long> findIdsByUserId(@Param("userId") Long userId);
}
