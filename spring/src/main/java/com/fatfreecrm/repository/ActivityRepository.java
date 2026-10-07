package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Activity;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    List<Activity> findBySubjectTypeAndSubjectId(String subjectType, Integer subjectId);

    default List<Activity> findBySubjectTypeAndSubjectId(RailsModelType type, Integer id) {
        return findBySubjectTypeAndSubjectId(type == null ? null : type.railsName(), id);
    }
}
