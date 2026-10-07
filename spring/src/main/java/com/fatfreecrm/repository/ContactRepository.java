package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Lead;
import java.util.List;

public interface ContactRepository extends CrmEntityRepository<Contact> {

    List<Contact> findByLead(Lead lead);

    List<Contact> findByReportsTo(Contact reportsTo);

    List<Contact> findByEmailIgnoreCase(String email);
}
