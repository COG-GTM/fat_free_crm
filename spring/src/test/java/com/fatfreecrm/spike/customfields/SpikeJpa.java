package com.fatfreecrm.spike.customfields;

import jakarta.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * Bootstraps a standalone JPA EntityManagerFactory for the spike entities,
 * with no Spring Boot context so nothing leaks into the app tests.
 */
final class SpikeJpa {

    private SpikeJpa() {
    }

    static EntityManagerFactory createEmf(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(dataSource);
        bean.setMappingResources("spike/customfields/spike-orm.xml");
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        bean.setPersistenceProviderClass(HibernatePersistenceProvider.class);
        bean.setPersistenceUnitName("spike-customfields");
        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", "none");
        props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        props.put("hibernate.session_factory.statement_inspector", SqlRecorder.class.getName());
        bean.setJpaPropertyMap(props);
        bean.afterPropertiesSet();
        return bean.getObject();
    }
}
