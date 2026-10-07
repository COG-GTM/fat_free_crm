package com.fatfreecrm.repository;

import com.fatfreecrm.domain.SavedList;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedListRepository extends JpaRepository<SavedList, Long> {
}
