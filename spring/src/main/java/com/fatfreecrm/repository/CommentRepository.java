package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.PolymorphicRef;
import com.fatfreecrm.domain.User;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findByCommentableOrderByCreatedAtAsc(PolymorphicRef commentable);

    List<Comment> findByUser(User user);
}
