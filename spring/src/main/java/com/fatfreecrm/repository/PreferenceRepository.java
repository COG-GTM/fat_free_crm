package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Preference;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PreferenceRepository extends JpaRepository<Preference, Long> {

    @Query("select preference from Preference preference where preference.user.id = :userId "
        + "and preference.name = :name")
    Optional<Preference> findByUserIdAndName(@Param("userId") Long userId, @Param("name") String name);
}
