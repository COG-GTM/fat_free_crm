package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.User;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class RansackParserTest {

    private final CriteriaBuilder builder = mock(CriteriaBuilder.class);
    @SuppressWarnings("unchecked")
    private final CriteriaQuery<User> query = mock(CriteriaQuery.class);
    @SuppressWarnings("unchecked")
    private final Root<User> root = mock(Root.class);
    @SuppressWarnings("unchecked")
    private final Path<Object> path = mock(Path.class);
    private final RansackParser parser = new RansackParser(
        new SearchableEntities(null, null, null),
        new StaticListableBeanFactory().getBeanProvider(DynamicAttributePredicates.class),
        true
    );

    @BeforeEach
    void stubAttributes() {
        when(root.get("suspendedAt")).thenReturn(path);
        when(root.get("username")).thenReturn(path);
        when(root.get("admin")).thenReturn(path);
    }

    @Test
    void falseValuesInvertNullPresentAndBooleanPredicates() {
        compile("suspended_at_null", "0");
        verify(builder).isNotNull(path);
        clearInvocations(builder);

        compile("suspended_at_not_null", "FALSE");
        verify(builder).isNull(path);
        clearInvocations(builder);

        compile("username_present", "no");
        verify(builder).isNull(path);
        verify(builder).equal(path, "");
        verify(builder).or(isNull(Predicate.class), isNull(Predicate.class));
        clearInvocations(builder);

        compile("username_blank", "off");
        verify(builder).isNotNull(path);
        verify(builder).notEqual(path, "");
        verify(builder).and(isNull(Predicate.class), isNull(Predicate.class));
        clearInvocations(builder);

        compile("admin_true", "n");
        verify(builder).notEqual(path, Boolean.TRUE);
        clearInvocations(builder);

        compile("admin_false", "NO");
        verify(builder).notEqual(path, Boolean.FALSE);
    }

    @Test
    void acceptsRansackBooleanTokensCaseInsensitivelyAndDropsBlankOrInvalidValues() {
        Set<String> trueValues = Set.of("1", "t", "true", "y", "yes", "on");
        for (String value : trueValues) {
            assertThat(plan("suspended_at_null", value.toUpperCase(java.util.Locale.ROOT)).where())
                .as("Ransack true value %s", value)
                .isNotNull();
        }
        Set<String> falseValues = Set.of("0", "f", "false", "n", "no", "off");
        for (String value : falseValues) {
            assertThat(plan("suspended_at_null", value.toUpperCase(java.util.Locale.ROOT)).where())
                .as("Ransack false value %s", value)
                .isNotNull();
        }
        assertThat(plan("suspended_at_null", " ").where()).isNull();
        assertThat(plan("suspended_at_null", "other").where()).isNull();
    }

    private void compile(String key, String value) {
        RansackParser.SearchPlan<User> plan = plan(key, value);
        assertThat(plan.where()).isNotNull();
        plan.where().toPredicate(root, query, builder);
    }

    private RansackParser.SearchPlan<User> plan(String key, String value) {
        return parser.parse(User.class, Map.of(key, value));
    }
}
