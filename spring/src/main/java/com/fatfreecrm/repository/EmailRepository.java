package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EmailRepository extends JpaRepository<Email, Long>, JpaSpecificationExecutor<Email> {

    List<Email> findByMediatorTypeAndMediatorId(String mediatorType, Integer mediatorId);

    default List<Email> findByMediatorTypeAndMediatorId(RailsModelType type, Integer id) {
        return findByMediatorTypeAndMediatorId(type == null ? null : type.railsName(), id);
    }
}
