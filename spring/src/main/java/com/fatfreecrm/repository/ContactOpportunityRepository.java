package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.ContactOpportunity;
import com.fatfreecrm.domain.Opportunity;
import java.util.List;

public interface ContactOpportunityRepository extends SoftDeletableRepository<ContactOpportunity> {

    List<ContactOpportunity> findByContact(Contact contact);

    List<ContactOpportunity> findByOpportunity(Opportunity opportunity);
}
