package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Tagging;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaggingRepository extends JpaRepository<Tagging, Long> {

    List<Tagging> findByTaggableTypeAndTaggableId(String taggableType, Integer taggableId);

    @EntityGraph(attributePaths = "tag")
    @Query("""
        select tagging from Tagging tagging
        where tagging.taggableType = :taggableType
          and tagging.context = :context
          and tagging.taggableId in :taggableIds
          and tagging.taggerId is null
        order by tagging.id
        """)
    List<Tagging> findByTaggableTypeAndContextAndTaggableIdInOrderById(
        @Param("taggableType") String taggableType,
        @Param("context") String context,
        @Param("taggableIds") Collection<Integer> taggableIds
    );

    default List<Tagging> findByTaggableTypeAndTaggableId(RailsModelType type, Integer id) {
        return findByTaggableTypeAndTaggableId(type == null ? null : type.railsName(), id);
    }

    List<Tagging> findByTaggerTypeAndTaggerId(String taggerType, Integer taggerId);

    default List<Tagging> findByTaggerTypeAndTaggerId(RailsModelType type, Integer id) {
        return findByTaggerTypeAndTaggerId(type == null ? null : type.railsName(), id);
    }
}
