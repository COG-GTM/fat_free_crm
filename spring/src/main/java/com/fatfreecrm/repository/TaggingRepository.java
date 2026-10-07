package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Tagging;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaggingRepository extends JpaRepository<Tagging, Long> {

    List<Tagging> findByTaggableTypeAndTaggableId(String taggableType, Integer taggableId);

    List<Tagging> findByTaggableTypeAndContextAndTaggableIdInOrderById(
        String taggableType, String context, Collection<Integer> taggableIds);

    default List<Tagging> findByTaggableTypeAndTaggableId(RailsModelType type, Integer id) {
        return findByTaggableTypeAndTaggableId(type == null ? null : type.railsName(), id);
    }

    List<Tagging> findByTaggerTypeAndTaggerId(String taggerType, Integer taggerId);

    default List<Tagging> findByTaggerTypeAndTaggerId(RailsModelType type, Integer id) {
        return findByTaggerTypeAndTaggerId(type == null ? null : type.railsName(), id);
    }
}
