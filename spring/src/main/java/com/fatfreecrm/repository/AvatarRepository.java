package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Avatar;
import com.fatfreecrm.domain.PolymorphicRef;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvatarRepository extends JpaRepository<Avatar, Long> {

    Optional<Avatar> findByEntity(PolymorphicRef entity);
}
