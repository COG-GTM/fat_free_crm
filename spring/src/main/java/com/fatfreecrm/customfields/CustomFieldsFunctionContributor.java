package com.fatfreecrm.customfields;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.type.BasicType;
import org.hibernate.type.StandardBasicTypes;

public class CustomFieldsFunctionContributor implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        SqmFunctionRegistry registry = contributions.getFunctionRegistry();
        var types = contributions.getTypeConfiguration().getBasicTypeRegistry();
        BasicType<Boolean> booleanType = types.resolve(StandardBasicTypes.BOOLEAN);
        BasicType<String> stringType = types.resolve(StandardBasicTypes.STRING);
        BasicType<java.math.BigDecimal> numericType = types.resolve(StandardBasicTypes.BIG_DECIMAL);
        BasicType<?> dateType = types.resolve(StandardBasicTypes.DATE);
        BasicType<?> timestampType = types.resolve(StandardBasicTypes.TIMESTAMP);

        registry.registerPattern("ffcrm_jsonb_contains", "(?1 @> cast(?2 as jsonb))", booleanType);
        registry.registerPattern("ffcrm_jsonb_text", "(?1 ->> ?2)", stringType);
        registry.registerPattern("ffcrm_jsonb_numeric", "cast((?1 ->> ?2) as numeric)", numericType);
        registry.registerPattern("ffcrm_jsonb_date", "cast((?1 ->> ?2) as date)", dateType);
        registry.registerPattern("ffcrm_jsonb_timestamp", "cast((?1 ->> ?2) as timestamp)", timestampType);
        registry.registerPattern("ffcrm_jsonb_boolean", "cast((?1 ->> ?2) as boolean)", booleanType);
    }
}
