package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Account;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AccountRepository extends JpaRepository<Account, Long>, JpaSpecificationExecutor<Account> {

    @Query("SELECT account.id FROM Account account WHERE account.user.id = :userId")
    List<Long> findIdsByUserId(@Param("userId") Long userId);
}
