package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    List<Task> findByAssetTypeAndAssetId(RailsModelType assetType, Integer assetId);

    @Query(value = "SELECT * FROM tasks ORDER BY id", nativeQuery = true)
    List<Task> findAllIncludingDeleted();

    @Query(value = "SELECT * FROM tasks WHERE id = :id", nativeQuery = true)
    Optional<Task> findByIdIncludingDeleted(@Param("id") Long id);
}
