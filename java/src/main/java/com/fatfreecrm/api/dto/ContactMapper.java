package com.fatfreecrm.api.dto;

import com.fatfreecrm.domain.Contact;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * {@link Contact} entity → {@link ContactDto}. All properties map by name; the only conversion
 * is the Rails-serialized {@code subscribed_users} YAML text → {@code List<Long>}, delegated to
 * {@link SubscribedUsersParser}.
 */
@Mapper(config = CentralMapperConfig.class)
public interface ContactMapper {

    ContactDto toDto(Contact contact);

    default List<Long> subscribedUsers(String yaml) {
        return SubscribedUsersParser.parse(yaml);
    }
}
