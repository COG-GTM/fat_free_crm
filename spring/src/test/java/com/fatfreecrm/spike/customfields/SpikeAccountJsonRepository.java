package com.fatfreecrm.spike.customfields;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;

/**
 * Spike repository; {@code @NoRepositoryBean} keeps Spring Boot's repository
 * scan from instantiating it. Obtained via {@code new JpaRepositoryFactory(em)}.
 */
@NoRepositoryBean
public interface SpikeAccountJsonRepository
    extends JpaRepository<SpikeAccountJson, Long>, JpaSpecificationExecutor<SpikeAccountJson> {

    @Query("select a from SpikeAccountJson a where spike_jsonb_contains(a.customFields, :json) = true")
    List<SpikeAccountJson> findContainingJpql(@Param("json") String json);

    @Query(nativeQuery = true,
        value = "select * from spike_accounts_json where custom_fields @> cast(:json as jsonb)")
    List<SpikeAccountJson> findContainingNative(@Param("json") String json);
}
