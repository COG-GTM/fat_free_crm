package com.fatfreecrm.repository;

import com.fatfreecrm.domain.PolymorphicRef;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import java.util.List;

public interface TaskRepository extends SoftDeletableRepository<Task> {

    List<Task> findByAsset(PolymorphicRef asset);

    List<Task> findByAssignedToAndCompletedAtIsNull(User assignedTo);

    List<Task> findByUserAndCompletedAtIsNull(User user);

    List<Task> findByCompletedAtIsNotNull();
}
