package com.fatfreecrm.repository;

import com.fatfreecrm.domain.AccountContact;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountContactRepository extends JpaRepository<AccountContact, Long> {

    @Query(value = "SELECT * FROM account_contacts ORDER BY id", nativeQuery = true)
    List<AccountContact> findAllIncludingDeleted();

    @Query(value = "SELECT * FROM account_contacts WHERE id = :id", nativeQuery = true)
    Optional<AccountContact> findByIdIncludingDeleted(@Param("id") Long id);
}
