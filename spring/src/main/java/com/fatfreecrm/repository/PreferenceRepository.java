package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Preference;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreferenceRepository extends JpaRepository<Preference, Long> {

    Optional<Preference> findFirstByUserIdAndNameOrderByIdAsc(Long userId, String name);
}
