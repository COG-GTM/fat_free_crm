package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Campaign;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository extends JpaRepository<Campaign, Long>, JpaSpecificationExecutor<Campaign> {

    @Query(value = "SELECT * FROM campaigns ORDER BY id", nativeQuery = true)
    List<Campaign> findAllIncludingDeleted();

    @Query(value = "SELECT * FROM campaigns WHERE id = :id", nativeQuery = true)
    Optional<Campaign> findByIdIncludingDeleted(@Param("id") Long id);
}
