package com.fatfreecrm.api.dto;

import com.fatfreecrm.domain.Account;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * {@link Account} entity → {@link AccountDto}. Property names line up one-to-one, so only the
 * two type conversions need helpers: the YAML {@code subscribedUsers} column and the zone-less
 * Rails timestamps (stored as UTC, see {@code config.active_record.default_timezone}).
 */
@Mapper(config = CentralMapperConfig.class)
public interface AccountMapper {

    AccountDto toDto(Account account);

    List<AccountDto> toDtos(List<Account> accounts);

    default List<Long> subscribedUsers(String yaml) {
        return SubscribedUsersParser.parse(yaml);
    }

    default OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
