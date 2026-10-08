package com.fatfreecrm.repository;

import com.fatfreecrm.domain.ContactOpportunity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContactOpportunityRepository extends JpaRepository<ContactOpportunity, Long> {

    @Query("SELECT contactOpportunity.opportunity.id FROM ContactOpportunity contactOpportunity "
        + "WHERE contactOpportunity.contact.id = :contactId")
    List<Long> findOpportunityIdsByContactId(@Param("contactId") Long contactId);
}
