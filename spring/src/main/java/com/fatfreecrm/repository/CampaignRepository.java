package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Campaign;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository extends JpaRepository<Campaign, Long>, JpaSpecificationExecutor<Campaign> {

    @Query("SELECT campaign.id FROM Campaign campaign WHERE campaign.user.id = :userId")
    List<Long> findIdsByUserId(@Param("userId") Long userId);
}
