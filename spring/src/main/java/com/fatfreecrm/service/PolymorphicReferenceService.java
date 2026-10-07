package com.fatfreecrm.service;

import com.fatfreecrm.domain.support.RailsModelType;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PolymorphicReferenceService {

    private final EntityManager entityManager;

    public PolymorphicReferenceService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<Object> resolve(RailsModelType type, Integer id) {
        if (type == null || id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entityManager.find(type.entityClass(), id.longValue()));
    }
}
