package com.fatfreecrm.repository;

import com.fatfreecrm.domain.CrmEntity;
import com.fatfreecrm.domain.User;
import java.util.List;
import org.springframework.data.repository.NoRepositoryBean;

/** Finders shared by the five core CRM entities (owner, assignee and access-level lookups). */
@NoRepositoryBean
public interface CrmEntityRepository<T extends CrmEntity> extends SoftDeletableRepository<T> {

    List<T> findByUser(User user);

    List<T> findByAssignedTo(User assignedTo);

    List<T> findByAccess(String access);
}
