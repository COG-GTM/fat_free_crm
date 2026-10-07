package com.fatfreecrm.repository;

import com.fatfreecrm.domain.PolymorphicRef;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.domain.Tagging;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaggingRepository extends JpaRepository<Tagging, Long> {

    List<Tagging> findByTaggable(PolymorphicRef taggable);

    List<Tagging> findByTag(Tag tag);
}
