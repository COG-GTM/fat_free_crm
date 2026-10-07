package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Tagging;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaggingRepository extends JpaRepository<Tagging, Long> {

    List<Tagging> findByTaggableTypeAndTaggableId(RailsModelType taggableType, Integer taggableId);

    List<Tagging> findByTaggerTypeAndTaggerId(RailsModelType taggerType, Integer taggerId);
}
