package com.fatfreecrm.config;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.accept.ParameterContentNegotiationStrategy;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * AB-273: {@code ?format=csv|xls} (Spring's parameter strategy) ahead of the default {@code Accept} header
 * strategy. Other {@code format} values are ignored, so JSON reads keep their current behavior.
 */
@Configuration
public class ExportContentNegotiationConfig implements WebMvcConfigurer {

    public static final MediaType TEXT_CSV = MediaType.parseMediaType("text/csv");
    public static final MediaType MS_EXCEL = MediaType.parseMediaType("application/vnd.ms-excel");

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        ParameterContentNegotiationStrategy parameter =
            new ParameterContentNegotiationStrategy(Map.of("csv", TEXT_CSV, "xls", MS_EXCEL));
        parameter.setParameterName("format");
        parameter.setUseRegisteredExtensionsOnly(true);
        parameter.setIgnoreUnknownExtensions(true);
        configurer.strategies(List.of(parameter, new HeaderContentNegotiationStrategy()));
    }
}
