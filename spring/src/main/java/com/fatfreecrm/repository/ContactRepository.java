package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Contact;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContactRepository extends JpaRepository<Contact, Long>, JpaSpecificationExecutor<Contact> {

    @Query("SELECT contact.id FROM Contact contact WHERE contact.user.id = :userId")
    List<Long> findIdsByUserId(@Param("userId") Long userId);

    List<Contact> findByLeadId(Long leadId);
}
