package com.fatfreecrm.repository;

import com.fatfreecrm.domain.Address;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AddressRepository extends JpaRepository<Address, Long> {

    List<Address> findByAddressableTypeAndAddressableId(RailsModelType addressableType, Integer addressableId);

    @Query(value = "SELECT * FROM addresses ORDER BY id", nativeQuery = true)
    List<Address> findAllIncludingDeleted();

    @Query(value = "SELECT * FROM addresses WHERE id = :id", nativeQuery = true)
    Optional<Address> findByIdIncludingDeleted(@Param("id") Long id);
}
