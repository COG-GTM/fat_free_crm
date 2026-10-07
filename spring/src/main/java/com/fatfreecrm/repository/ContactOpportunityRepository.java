package com.fatfreecrm.repository;

import com.fatfreecrm.domain.ContactOpportunity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContactOpportunityRepository extends JpaRepository<ContactOpportunity, Long> {

    @Query(value = "SELECT * FROM contact_opportunities ORDER BY id", nativeQuery = true)
    List<ContactOpportunity> findAllIncludingDeleted();

    @Query(value = "SELECT * FROM contact_opportunities WHERE id = :id", nativeQuery = true)
    Optional<ContactOpportunity> findByIdIncludingDeleted(@Param("id") Long id);
}
