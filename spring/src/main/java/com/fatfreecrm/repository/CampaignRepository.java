package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Campaign;
import java.util.List;

public interface CampaignRepository extends CrmEntityRepository<Campaign> {

    List<Campaign> findByStatus(String status);
}
