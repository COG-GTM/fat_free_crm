package com.fatfreecrm.repository;

import com.fatfreecrm.domain.FieldGroup;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldGroupRepository extends JpaRepository<FieldGroup, Long> {

    List<FieldGroup> findByKlassNameOrderByPositionAsc(String klassName);
}
