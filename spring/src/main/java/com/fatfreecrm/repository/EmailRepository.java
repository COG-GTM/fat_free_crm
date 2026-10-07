package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailRepository extends JpaRepository<Email, Long> {

    List<Email> findByMediatorTypeAndMediatorId(RailsModelType mediatorType, Integer mediatorId);

    @Query(value = "SELECT * FROM emails ORDER BY id", nativeQuery = true)
    List<Email> findAllIncludingDeleted();

    @Query(value = "SELECT * FROM emails WHERE id = :id", nativeQuery = true)
    Optional<Email> findByIdIncludingDeleted(@Param("id") Long id);
}
