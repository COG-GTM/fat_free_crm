package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Address;
import com.fatfreecrm.domain.PolymorphicRef;
import java.util.List;
import java.util.Optional;

public interface AddressRepository extends SoftDeletableRepository<Address> {

    List<Address> findByAddressable(PolymorphicRef addressable);

    Optional<Address> findByAddressableAndAddressType(PolymorphicRef addressable, String addressType);
}
