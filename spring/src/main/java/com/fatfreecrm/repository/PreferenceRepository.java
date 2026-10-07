package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Preference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreferenceRepository extends JpaRepository<Preference, Long> {
}
