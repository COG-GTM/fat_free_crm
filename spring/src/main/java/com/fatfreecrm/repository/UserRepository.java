package com.fatfreecrm.repository;

import com.fatfreecrm.domain.User;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("""
        SELECT u FROM User u
        WHERE lower(u.username) = :login OR lower(u.email) = :login
        ORDER BY u.id ASC
        """)
    List<User> findByLogin(@Param("login") String login, Limit limit);
}
