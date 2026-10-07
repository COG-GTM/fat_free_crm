package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Version;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VersionRepository extends JpaRepository<Version, Long> {

    List<Version> findByItemTypeAndItemId(String itemType, Integer itemId);

    default List<Version> findByItemTypeAndItemId(RailsModelType type, Integer id) {
        return findByItemTypeAndItemId(type == null ? null : type.railsName(), id);
    }

    List<Version> findByRelatedTypeAndRelatedId(String relatedType, Integer relatedId);

    default List<Version> findByRelatedTypeAndRelatedId(RailsModelType type, Integer id) {
        return findByRelatedTypeAndRelatedId(type == null ? null : type.railsName(), id);
    }
}
