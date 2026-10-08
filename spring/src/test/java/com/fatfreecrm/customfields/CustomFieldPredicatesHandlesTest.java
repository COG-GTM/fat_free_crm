package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.RailsModelType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CustomFieldPredicates#handles} is the gate through which {@code RansackParser} routes unknown
 * attributes. It must only claim registered {@code cf_*} names on the six Fields-bearing models so that
 * typos fall through to the parser's unknown-condition handling instead of generating JSONB SQL.
 */
class CustomFieldPredicatesHandlesTest {

    private CustomFieldRegistry registry;
    private CustomFieldPredicates predicates;

    @BeforeEach
    void setUp() {
        registry = mock(CustomFieldRegistry.class);
        predicates = new CustomFieldPredicates(registry, new ObjectMapper());
    }

    @Test
    void claimsRegisteredCustomFieldsOnFieldsBearingModels() {
        CustomFieldDefinition definition = new CustomFieldDefinition(
            "cf_tier", "Tier", "string", false, null, null, List.of(), 1L, null);
        when(registry.find(RailsModelType.ACCOUNT, "cf_tier")).thenReturn(Optional.of(definition));
        when(registry.find(RailsModelType.CONTACT, "cf_tier")).thenReturn(Optional.of(definition));

        assertThat(predicates.handles(Account.class, "cf_tier")).isTrue();
        assertThat(predicates.handles(Contact.class, "cf_tier")).isTrue();
    }

    @Test
    void declinesUnregisteredCustomFields() {
        when(registry.find(any(), anyString())).thenReturn(Optional.empty());

        assertThat(predicates.handles(Account.class, "cf_missing")).isFalse();
        verify(registry).find(RailsModelType.ACCOUNT, "cf_missing");
    }

    @Test
    void declinesAttributesThatAreNotRailsCustomFieldNamesWithoutHittingTheRegistry() {
        assertThat(predicates.handles(Account.class, "name")).isFalse();
        assertThat(predicates.handles(Account.class, "cf_")).isFalse();
        assertThat(predicates.handles(Account.class, "cf_Upper")).isFalse();
        assertThat(predicates.handles(Account.class, "cf_a-b")).isFalse();
        assertThat(predicates.handles(Account.class, "xcf_tier")).isFalse();
        verify(registry, never()).find(any(), anyString());
    }

    @Test
    void declinesModelsWithoutCustomFieldsWithoutHittingTheRegistry() {
        assertThat(predicates.handles(User.class, "cf_tier")).isFalse();
        assertThat(predicates.handles(Comment.class, "cf_tier")).isFalse();
        assertThat(predicates.handles(String.class, "cf_tier")).isFalse();
        verify(registry, never()).find(any(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toPredicateIsNullForUnknownFieldsOrNoValuesAndRejectsForeignEntities() {
        Root<Account> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CustomFieldDefinition definition = new CustomFieldDefinition(
            "cf_tier", "Tier", "string", false, null, null, List.of(), 1L, null);
        when(registry.find(RailsModelType.ACCOUNT, "cf_tier")).thenReturn(Optional.of(definition));
        when(registry.find(RailsModelType.ACCOUNT, "cf_missing")).thenReturn(Optional.empty());

        assertThat(predicates.toPredicate(root, query, cb, Account.class, "cf_missing", "eq", List.of("x")))
            .isNull();
        assertThat(predicates.toPredicate(root, query, cb, Account.class, "cf_tier", "eq", List.of()))
            .isNull();
        Root<User> userRoot = mock(Root.class);
        assertThatThrownBy(() -> predicates.toPredicate(userRoot, query, cb, User.class, "cf_tier", "eq",
            List.of("x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(User.class.getName());
    }
}
