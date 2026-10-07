package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Opportunity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpportunityRepository extends JpaRepository<Opportunity, Long>,
        JpaSpecificationExecutor<Opportunity> {

    @Query(value = "SELECT * FROM opportunities ORDER BY id", nativeQuery = true)
    List<Opportunity> findAllIncludingDeleted();

    @Query(value = "SELECT * FROM opportunities WHERE id = :id", nativeQuery = true)
    Optional<Opportunity> findByIdIncludingDeleted(@Param("id") Long id);
}
