package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Account;
import java.util.Optional;

public interface AccountRepository extends CrmEntityRepository<Account> {

    Optional<Account> findByName(String name);
}
