package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    List<Task> findByAssetTypeAndAssetId(String assetType, Integer assetId);

    default List<Task> findByAssetTypeAndAssetId(RailsModelType assetType, Integer assetId) {
        return findByAssetTypeAndAssetId(assetType == null ? null : assetType.railsName(), assetId);
    }
}
