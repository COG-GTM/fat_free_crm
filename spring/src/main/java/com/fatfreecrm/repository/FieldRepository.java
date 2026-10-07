package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Field;
import com.fatfreecrm.domain.FieldGroup;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldRepository extends JpaRepository<Field, Long> {

    List<Field> findByFieldGroupOrderByPositionAsc(FieldGroup fieldGroup);

    List<Field> findByType(String type);
}
