package com.fatfreecrm.service.read;

import com.fatfreecrm.domain.Version;
import com.fatfreecrm.repository.VersionRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.json.RailsResource;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecentlyViewedService {

    private final VersionRepository versionRepository;

    public RecentlyViewedService(VersionRepository versionRepository) {
        this.versionRepository = versionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordView(AuthenticatedUser user, RailsResource resource, long id) {
        Version version = new Version();
        version.setItemType(resource.railsModel());
        version.setItemId(Math.toIntExact(id));
        version.setEvent("view");
        version.setWhodunnit(String.valueOf(user.id()));
        version.setCreatedAt(Instant.now());
        versionRepository.saveAndFlush(version);
    }
}
