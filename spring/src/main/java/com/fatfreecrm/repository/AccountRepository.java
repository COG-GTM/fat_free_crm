package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Account;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long>, JpaSpecificationExecutor<Account> {

    @Query(value = "SELECT * FROM accounts ORDER BY id", nativeQuery = true)
    List<Account> findAllIncludingDeleted();

    @Query(value = "SELECT * FROM accounts WHERE id = :id", nativeQuery = true)
    Optional<Account> findByIdIncludingDeleted(@Param("id") Long id);
}
