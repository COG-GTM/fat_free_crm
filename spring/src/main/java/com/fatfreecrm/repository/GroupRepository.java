package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Group;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupRepository extends JpaRepository<Group, Long> {

    Optional<Group> findByName(String name);

    /**
     * Reads membership straight from the Rails {@code groups_users} join table,
     * whose {@code integer} keys cannot be mapped as a JPA association against
     * {@code bigint} primary keys.
     */
    @Query(value = "SELECT g.* FROM groups g JOIN groups_users gu ON gu.group_id = g.id "
        + "WHERE gu.user_id = :userId ORDER BY g.id", nativeQuery = true)
    List<Group> findGroupsForUser(@Param("userId") Long userId);
}
