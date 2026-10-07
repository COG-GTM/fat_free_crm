package com.fatfreecrm.repository;

import com.fatfreecrm.domain.AccountOpportunity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountOpportunityRepository extends JpaRepository<AccountOpportunity, Long> {

    @Query(value = "SELECT * FROM account_opportunities ORDER BY id", nativeQuery = true)
    List<AccountOpportunity> findAllIncludingDeleted();

    @Query(value = "SELECT * FROM account_opportunities WHERE id = :id", nativeQuery = true)
    Optional<AccountOpportunity> findByIdIncludingDeleted(@Param("id") Long id);
}
