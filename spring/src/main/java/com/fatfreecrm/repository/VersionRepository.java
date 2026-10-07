package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Version;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VersionRepository extends JpaRepository<Version, Long> {

    List<Version> findByItemTypeAndItemId(RailsModelType itemType, Integer itemId);

    List<Version> findByRelatedTypeAndRelatedId(RailsModelType relatedType, Integer relatedId);
}
