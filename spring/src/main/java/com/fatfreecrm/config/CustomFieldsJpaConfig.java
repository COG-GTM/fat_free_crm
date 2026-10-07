package com.fatfreecrm.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomFieldsJpaConfig {

    @Bean
    HibernatePropertiesCustomizer customFieldsJsonFormatMapper() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        return properties -> properties.put(
            AvailableSettings.JSON_FORMAT_MAPPER, new JacksonJsonFormatMapper(mapper));
    }
}
