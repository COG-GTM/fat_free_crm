package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Activity;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    List<Activity> findBySubjectTypeAndSubjectId(RailsModelType subjectType, Integer subjectId);
}
