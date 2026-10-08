package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.RailsModelType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure routing rules of the Ransack extension point: which attributes the custom-field predicates claim
 * and which (attribute type, predicate) combinations Rails Ransack rejects, so the Java side must not
 * silently produce a different result set.
 */
class CustomFieldPredicatesTest {

    private CustomFieldRegistry registry;
    private CustomFieldPredicates predicates;

    @BeforeEach
    void setUp() {
        registry = mock(CustomFieldRegistry.class);
        predicates = new CustomFieldPredicates(registry, new ObjectMapper());
    }

    @Test
    void handlesOnlyRegisteredCfAttributesOfTheSixCustomFieldModels() {
        when(registry.find(any(), any())).thenReturn(Optional.empty());
        for (RailsModelType type : List.of(RailsModelType.ACCOUNT, RailsModelType.CAMPAIGN,
            RailsModelType.CONTACT, RailsModelType.LEAD, RailsModelType.OPPORTUNITY, RailsModelType.TASK)) {
            when(registry.find(eq(type), eq("cf_region"))).thenReturn(Optional.of(definition(type, "string")));
        }

        for (Class<?> entity : List.of(Account.class, Campaign.class, Contact.class, Lead.class,
            Opportunity.class, Task.class)) {
            assertThat(predicates.handles(entity, "cf_region")).as(entity.getSimpleName()).isTrue();
            assertThat(predicates.handles(entity, "cf_unknown")).as(entity.getSimpleName()).isFalse();
            assertThat(predicates.handles(entity, "name")).as(entity.getSimpleName()).isFalse();
        }
    }

    @Test
    void neverClaimsAttributesOfModelsWithoutCustomFields() {
        assertThat(predicates.handles(User.class, "cf_region")).isFalse();
        assertThat(predicates.handles(String.class, "cf_region")).isFalse();
        verifyNoInteractions(registry);
    }

    @Test
    void ignoresAttributesThatAreNotValidRailsCustomFieldColumnNames() {
        for (String attribute : List.of("cf_Region", "cf_", "cf_region-x", "cf_region;drop", "xcf_region")) {
            assertThat(predicates.handles(Account.class, attribute)).as(attribute).isFalse();
        }
        verifyNoInteractions(registry);
    }

    @Test
    void producesNoPredicateForUnknownFieldsOrEmptyValues() {
        when(registry.find(RailsModelType.ACCOUNT, "cf_region"))
            .thenReturn(Optional.of(definition(RailsModelType.ACCOUNT, "string")));
        when(registry.find(RailsModelType.ACCOUNT, "cf_missing")).thenReturn(Optional.empty());

        assertThat(toPredicate("cf_missing", "eq", List.of("x"))).isNull();
        assertThat(toPredicate("cf_region", "eq", List.of())).isNull();
    }

    @Test
    void rejectsPredicateCombinationsRansackRejectsOnTheRailsColumnTypes() {
        register("cf_boxes", "check_boxes");
        register("cf_count", "integer");
        register("cf_price", "decimal");
        register("cf_ratio", "float");
        register("cf_day", "date");
        register("cf_at", "datetime");
        register("cf_flag", "boolean");

        for (String predicate : List.of("eq", "not_eq", "in", "not_in", "lt", "lteq", "gt", "gteq",
            "blank", "present", "true", "false")) {
            assertThat(toPredicate("cf_boxes", predicate, List.of("Option A"))).as("check_boxes " + predicate).isNull();
        }
        for (String field : List.of("cf_count", "cf_price", "cf_ratio", "cf_day", "cf_at", "cf_flag")) {
            for (String predicate : List.of("cont", "not_cont", "i_cont", "start", "not_start", "end",
                "not_end", "matches", "does_not_match")) {
                assertThat(toPredicate(field, predicate, List.of("1"))).as(field + " " + predicate).isNull();
            }
        }
        for (String field : List.of("cf_day", "cf_at")) {
            assertThat(toPredicate(field, "true", List.of("1"))).as(field + " true").isNull();
            assertThat(toPredicate(field, "false", List.of("1"))).as(field + " false").isNull();
        }
        for (String predicate : List.of("eq", "not_eq", "in", "not_in", "lt", "lteq", "gt", "gteq")) {
            assertThat(toPredicate("cf_flag", predicate, List.of("maybe"))).as("boolean " + predicate).isNull();
        }
    }

    @Test
    void refusesEntitiesThatCannotCarryCustomFields() {
        assertThatThrownBy(() -> predicates.toPredicate(
            mockRoot(), mock(CriteriaQuery.class), mock(CriteriaBuilder.class), User.class,
            "cf_region", "eq", List.of("x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("User");
    }

    private void register(String name, String as) {
        when(registry.find(RailsModelType.ACCOUNT, name))
            .thenReturn(Optional.of(definition(RailsModelType.ACCOUNT, name, as)));
    }

    private static CustomFieldDefinition definition(RailsModelType type, String as) {
        return definition(type, "cf_region", as);
    }

    private static CustomFieldDefinition definition(RailsModelType type, String name, String as) {
        return new CustomFieldDefinition(1L, "CustomField", 1L, type, 1, 1, name, name, null, null, as,
            List.of(), false, false, null, null, null, Map.of());
    }

    private jakarta.persistence.criteria.Predicate toPredicate(String attribute, String operator, List<String> values) {
        return predicates.toPredicate(mockRoot(), mock(CriteriaQuery.class), mock(CriteriaBuilder.class),
            Account.class, attribute, operator, values);
    }

    @SuppressWarnings("unchecked")
    private static <T> Root<T> mockRoot() {
        return (Root<T>) mock(Root.class);
    }
}
