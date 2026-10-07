package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Opportunity;
import java.util.List;

public interface OpportunityRepository extends CrmEntityRepository<Opportunity> {

    List<Opportunity> findByCampaign(Campaign campaign);

    List<Opportunity> findByStage(String stage);
}
