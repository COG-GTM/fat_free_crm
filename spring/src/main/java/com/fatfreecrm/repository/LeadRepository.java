package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Lead;
import java.util.List;

public interface LeadRepository extends CrmEntityRepository<Lead> {

    List<Lead> findByCampaign(Campaign campaign);

    List<Lead> findByStatus(String status);
}
