package com.fatfreecrm.service.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.AccountContact;
import com.fatfreecrm.domain.AccountOpportunity;
import com.fatfreecrm.domain.Address;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaperTrailOptionsTest {

    @Test
    void mirrorsTheRailsDeclarationsMissingFromTheInitialRegistry() {
        assertThat(PaperTrailOptions.forClass(User.class)).contains(
            new PaperTrailOptions(RailsModelType.USER, Set.of("last_sign_in_at"), null));
        assertThat(PaperTrailOptions.forClass(Address.class)).contains(
            new PaperTrailOptions(RailsModelType.ADDRESS, Set.of(), "addressable"));
        assertThat(PaperTrailOptions.forClass(AccountContact.class)).contains(
            new PaperTrailOptions(RailsModelType.ACCOUNT_CONTACT,
                Set.of("id", "created_at", "updated_at", "contact_id"), "contact"));
        assertThat(PaperTrailOptions.forClass(AccountOpportunity.class)).contains(
            new PaperTrailOptions(RailsModelType.ACCOUNT_OPPORTUNITY, Set.of(), null));
    }
}
