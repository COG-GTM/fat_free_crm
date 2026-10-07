package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.domain.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreferenceRepository extends JpaRepository<Preference, Long> {

    Optional<Preference> findByUserAndName(User user, String name);

    List<Preference> findByUser(User user);
}
