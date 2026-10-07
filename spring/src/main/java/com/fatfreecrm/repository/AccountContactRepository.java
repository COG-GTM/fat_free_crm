package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.AccountContact;
import com.fatfreecrm.domain.Contact;
import java.util.List;
import java.util.Optional;

public interface AccountContactRepository extends SoftDeletableRepository<AccountContact> {

    List<AccountContact> findByAccount(Account account);

    Optional<AccountContact> findByContact(Contact contact);
}
