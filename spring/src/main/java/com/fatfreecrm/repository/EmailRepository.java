package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.PolymorphicRef;
import java.util.List;
import java.util.Optional;

public interface EmailRepository extends SoftDeletableRepository<Email> {

    List<Email> findByMediatorOrderBySentAtDesc(PolymorphicRef mediator);

    Optional<Email> findByImapMessageId(String imapMessageId);
}
