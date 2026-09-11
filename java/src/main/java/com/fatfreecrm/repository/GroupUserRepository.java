package com.fatfreecrm.repository;

import com.fatfreecrm.domain.GroupUser;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupUserRepository extends JpaRepository<GroupUser, GroupUser.Key> {

    @Query("select gu.key.groupId from GroupUser gu where gu.key.userId = :userId")
    List<Long> findGroupIdsByUserId(@Param("userId") Long userId);
}
