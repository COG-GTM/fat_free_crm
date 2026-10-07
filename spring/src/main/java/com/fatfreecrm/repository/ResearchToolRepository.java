package com.fatfreecrm.repository;

import com.fatfreecrm.domain.ResearchTool;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchToolRepository extends JpaRepository<ResearchTool, Long> {

    List<ResearchTool> findByEnabledTrueOrderByNameAsc();
}
