package com.fatfreecrm.spike.customfields;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.type.BasicType;
import org.hibernate.type.StandardBasicTypes;

/**
 * Registers spike-prefixed SQL function patterns exposing the PostgreSQL jsonb
 * operators to HQL and Criteria. Registered via
 * META-INF/services/org.hibernate.boot.model.FunctionContributor (test classpath).
 */
public class SpikeJsonbFunctionContributor implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        SqmFunctionRegistry registry = contributions.getFunctionRegistry();
        BasicType<Boolean> booleanType =
            contributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.BOOLEAN);
        BasicType<String> stringType =
            contributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.STRING);

        // jsonb containment operator: custom_fields @> '{"k":"v"}'::jsonb
        registry.registerPattern("spike_jsonb_contains", "(?1 @> cast(?2 as jsonb))", booleanType);
        // jsonpath match operator: custom_fields @? '$.k ? (@ > 10)'::jsonpath.
        // Not a pattern: PatternRenderer parses every literal '?' as a parameter
        // placeholder, so a custom renderer emits the operator text instead.
        registry.register("spike_jsonb_path_match",
            new SpikeJsonbPathMatchDescriptor(contributions.getTypeConfiguration()));
        // text extraction operator: custom_fields ->> 'k'
        registry.registerPattern("spike_jsonb_text", "(?1 ->> ?2)", stringType);
    }
}
