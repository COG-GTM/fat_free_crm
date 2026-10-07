package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Activity;
import com.fatfreecrm.domain.PolymorphicRef;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    List<Activity> findBySubjectOrderByCreatedAtDesc(PolymorphicRef subject);
}
