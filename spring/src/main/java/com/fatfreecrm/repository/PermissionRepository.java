package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    List<Permission> findByAssetTypeAndAssetId(RailsModelType assetType, Integer assetId);
}
