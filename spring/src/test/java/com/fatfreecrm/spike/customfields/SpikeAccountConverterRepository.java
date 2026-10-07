package com.fatfreecrm.spike.customfields;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Spike repository; {@code @NoRepositoryBean} keeps Spring Boot's repository
 * scan from instantiating it. Obtained via {@code new JpaRepositoryFactory(em)}.
 */
@NoRepositoryBean
public interface SpikeAccountConverterRepository
    extends JpaRepository<SpikeAccountConverter, Long>, JpaSpecificationExecutor<SpikeAccountConverter> {
}
