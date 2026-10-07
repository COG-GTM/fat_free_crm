package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Lead;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {

    @Query(value = "SELECT * FROM leads ORDER BY id", nativeQuery = true)
    List<Lead> findAllIncludingDeleted();

    @Query(value = "SELECT * FROM leads WHERE id = :id", nativeQuery = true)
    Optional<Lead> findByIdIncludingDeleted(@Param("id") Long id);
}
