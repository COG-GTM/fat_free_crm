package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Permission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    List<Permission> findByAssetTypeAndAssetId(String assetType, Integer assetId);

    List<Permission> findByUserId(Integer userId);

    List<Permission> findByGroupIdIn(List<Integer> groupIds);
}
