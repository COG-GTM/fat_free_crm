package com.fatfreecrm.api.support;

import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import com.fatfreecrm.service.json.RailsResource;
import com.fatfreecrm.service.query.ListQuery;
import com.fatfreecrm.service.read.UserPreferenceService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

@Component
public class CrmApiRequestSupport {

    private final UserPreferenceService userPreferenceService;

    public CrmApiRequestSupport(UserPreferenceService userPreferenceService) {
        this.userPreferenceService = userPreferenceService;
    }

    public AuthenticatedUser authenticatedUser(Authentication authentication) {
        return ((FfcrmAuthenticationToken) authentication).getAuthenticatedUser();
    }

    public ListQuery listQuery(
        AuthenticatedUser user,
        RailsResource resource,
        MultiValueMap<String, String> params,
        String filter
    ) {
        UserPreferenceService.ListDefaults defaults =
            userPreferenceService.listDefaults(user.id(), resource.controllerName());
        return ListQuery.fromParameters(params)
            .withPreferences(defaults.perPage(), defaults.sortBy())
            .withFilter(filter);
    }
}
