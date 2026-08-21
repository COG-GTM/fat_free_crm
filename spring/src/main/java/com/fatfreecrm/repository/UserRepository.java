package com.fatfreecrm.repository;

import com.fatfreecrm.domain.User;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Matches Rails' case-insensitive username-or-email lookup, preferring a
     * username match when both columns match the same login.
     */
    @Query("""
        SELECT u
        FROM User u
        WHERE lower(u.username) = :login
           OR lower(u.email) = :login
        ORDER BY CASE WHEN lower(u.username) = :login THEN 0 ELSE 1 END, u.id
        """)
    List<User> findByLogin(@Param("login") String login);
}
