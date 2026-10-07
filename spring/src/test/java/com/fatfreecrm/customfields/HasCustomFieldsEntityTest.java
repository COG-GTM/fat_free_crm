package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.support.HasCustomFields;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every Rails model with {@code has_fields} must expose a null-safe, read-only JSONB view. */
class HasCustomFieldsEntityTest {

    private static final List<HasCustomFields> ENTITIES = List.of(
        new Account(), new Campaign(), new Contact(), new Lead(), new Opportunity(), new Task());

    @Test
    void unsetCustomFieldsReadAsEmptyMapOnEveryEntity() {
        for (HasCustomFields entity : ENTITIES) {
            assertThat(entity.getCustomFields())
                .as("%s custom fields", entity.getClass().getSimpleName())
                .isEmpty();
        }
    }

    @Test
    void customFieldsViewIsUnmodifiableOnEveryEntity() {
        for (HasCustomFields entity : ENTITIES) {
            assertThatThrownBy(() -> entity.getCustomFields().put("cf_x", "y"))
                .as("%s custom fields", entity.getClass().getSimpleName())
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void everyHasFieldsRailsModelImplementsHasCustomFields() {
        List<RailsModelType> hasFieldsModels = List.of(RailsModelType.ACCOUNT, RailsModelType.CAMPAIGN,
            RailsModelType.CONTACT, RailsModelType.LEAD, RailsModelType.OPPORTUNITY, RailsModelType.TASK);
        for (RailsModelType type : hasFieldsModels) {
            assertThat(HasCustomFields.class.isAssignableFrom(type.entityClass()))
                .as("%s implements HasCustomFields", type.railsName())
                .isTrue();
        }
    }
}
