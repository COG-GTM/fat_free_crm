package com.fatfreecrm.repository;

import com.fatfreecrm.domain.AccountContact;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountContactRepository extends JpaRepository<AccountContact, Long> {

    @Query("SELECT link.contact.id FROM AccountContact link WHERE link.account.id = :accountId")
    List<Long> findContactIdsByAccountId(@Param("accountId") Long accountId);

    @Query("SELECT link.account.id FROM AccountContact link WHERE link.contact.id = :contactId")
    List<Long> findAccountIdsByContactId(@Param("contactId") Long contactId);
}
