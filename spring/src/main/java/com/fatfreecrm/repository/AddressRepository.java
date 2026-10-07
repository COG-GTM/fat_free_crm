package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Address;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, Long> {

    List<Address> findByAddressableTypeAndAddressableId(String addressableType, Integer addressableId);

    default List<Address> findByAddressableTypeAndAddressableId(RailsModelType type, Integer id) {
        return findByAddressableTypeAndAddressableId(type == null ? null : type.railsName(), id);
    }
}
