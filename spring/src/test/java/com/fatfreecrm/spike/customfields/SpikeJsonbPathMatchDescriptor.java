package com.fatfreecrm.spike.customfields;

import java.util.List;
import org.hibernate.query.ReturnableType;
import org.hibernate.query.sqm.function.NamedSqmFunctionDescriptor;
import org.hibernate.query.sqm.produce.function.StandardArgumentsValidators;
import org.hibernate.query.sqm.produce.function.StandardFunctionReturnTypeResolvers;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * Renders {@code spike_jsonb_path_match(a, p)} as the PostgreSQL
 * {@code @?} operator: {@code (a @? cast(p as jsonpath))}. A plain
 * {@code registerPattern} cannot express this because PatternRenderer treats
 * every literal {@code ?} as a parameter placeholder, with no escape.
 */
public class SpikeJsonbPathMatchDescriptor extends NamedSqmFunctionDescriptor {

    public SpikeJsonbPathMatchDescriptor(TypeConfiguration typeConfiguration) {
        super("spike_jsonb_path_match", false, StandardArgumentsValidators.exactly(2),
            StandardFunctionReturnTypeResolvers.invariant(
                typeConfiguration.getBasicTypeRegistry().resolve(StandardBasicTypes.BOOLEAN)));
    }

    @Override
    public void render(SqlAppender sqlAppender, List<? extends SqlAstNode> sqlAstArguments,
        ReturnableType<?> returnType, SqlAstTranslator<?> walker) {
        sqlAppender.appendSql('(');
        sqlAstArguments.get(0).accept(walker);
        sqlAppender.appendSql(" @? cast(");
        sqlAstArguments.get(1).accept(walker);
        sqlAppender.appendSql(" as jsonpath))");
    }
}
