package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Contact;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContactRepository extends JpaRepository<Contact, Long>, JpaSpecificationExecutor<Contact> {

    @Query(value = "SELECT * FROM contacts ORDER BY id", nativeQuery = true)
    List<Contact> findAllIncludingDeleted();

    @Query(value = "SELECT * FROM contacts WHERE id = :id", nativeQuery = true)
    Optional<Contact> findByIdIncludingDeleted(@Param("id") Long id);
}
