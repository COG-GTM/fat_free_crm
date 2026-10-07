package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Opportunity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OpportunityRepository extends JpaRepository<Opportunity, Long>,
        JpaSpecificationExecutor<Opportunity> {
}
