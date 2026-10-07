package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findByCommentableTypeAndCommentableId(RailsModelType commentableType, Integer commentableId);
}
