package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Tag;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TagRepository extends JpaRepository<Tag, Long> {

    @Query(value = "SELECT id::bigint FROM tags ORDER BY id ASC", nativeQuery = true)
    List<Long> findIdsInIdOrder();
}
