package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Avatar;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvatarRepository extends JpaRepository<Avatar, Long> {

    List<Avatar> findByEntityTypeAndEntityId(RailsModelType entityType, Integer entityId);
}
