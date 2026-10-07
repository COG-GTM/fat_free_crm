package com.fatfreecrm.repository;

import com.fatfreecrm.domain.PolymorphicRef;
import com.fatfreecrm.domain.Version;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Read-only access to PaperTrail history; Rails owns writes to {@code versions}. */
public interface VersionRepository extends Repository<Version, Long> {

    Optional<Version> findById(Long id);

    List<Version> findByItemOrderByCreatedAtDesc(PolymorphicRef item);

    List<Version> findByRelatedOrderByCreatedAtDesc(PolymorphicRef related);

    long count();
}
