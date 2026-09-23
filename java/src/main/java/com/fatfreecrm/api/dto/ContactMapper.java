package com.fatfreecrm.api.dto;

import com.fatfreecrm.domain.Contact;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * {@link Contact} entity → {@link ContactDto}. All properties map by name; the only conversions
 * are the Rails-serialized {@code subscribed_users} YAML text → {@code List<Long>}
 * ({@link SubscribedUsersParser}) and the UTC timestamps → {@link OffsetDateTime}.
 */
@Mapper(config = CentralMapperConfig.class)
public interface ContactMapper {

    ContactDto toDto(Contact contact);

    default List<Long> subscribedUsers(String yaml) {
        return SubscribedUsersParser.parse(yaml);
    }

    default OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
