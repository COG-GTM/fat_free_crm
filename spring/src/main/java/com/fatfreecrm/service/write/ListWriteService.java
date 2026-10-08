package com.fatfreecrm.service.write;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.SavedList;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.SavedListRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.validation.ActiveModelMessages;
import com.fatfreecrm.service.validation.RailsErrors;
import com.fatfreecrm.service.validation.RailsValidationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rails {@code ListsController} — no authorization anywhere (mirrored; flagged as a Rails security
 * gap in the ADR):
 *
 * <ul>
 *   <li>create: attrs from {@code list} (name, url, user_id); {@code is_global != "1"} forces
 *   {@code user_id = current_user}. Then an upsert by {@code lower(name)} + {@code user_id}
 *   (NULL-safe like {@code where(user_id: nil)}): update on hit, create otherwise. Always 201 on
 *   success; blank name/url → 422. Rails' {@code where("lower(name) = ?", name.downcase)} →
 *   {@code nil.downcase} 500 on a missing name is documented as a deviation (Spring 422).</li>
 *   <li>destroy: {@code List.find(params[:id]).destroy} for ANY user (no owner check — mirrored,
 *   open question). {@code List} has no paper trail.</li>
 * </ul>
 */
@Service
public class ListWriteService {

    private final SavedListRepository savedListRepository;
    private final RailsJsonWriter jsonWriter;
    private final RailsResources railsResources;
    private final ActiveModelMessages messages;
    private final EntityManager entityManager;

    public ListWriteService(
        SavedListRepository savedListRepository,
        RailsJsonWriter jsonWriter,
        RailsResources railsResources,
        ActiveModelMessages messages,
        EntityManager entityManager
    ) {
        this.savedListRepository = savedListRepository;
        this.jsonWriter = jsonWriter;
        this.railsResources = railsResources;
        this.messages = messages;
        this.entityManager = entityManager;
    }

    @Transactional
    public ObjectNode create(AuthenticatedUser user, RailsParams params, boolean global) {
        String name = RailsParams.asString(params.get("name").orElse(null));
        String url = RailsParams.asString(params.get("url").orElse(null));
        Long userId = global
            ? (params.provided("user_id")
                ? toLong(RailsParams.asInteger(params.get("user_id").orElse(null)))
                : null)
            : user.id();

        RailsErrors errors = new RailsErrors();
        if (name == null || name.isBlank()) {
            errors.add(messages, "list", "name", "blank");
        }
        if (url == null || url.isBlank()) {
            errors.add(messages, "list", "url", "blank");
        }
        if (!errors.isEmpty()) {
            throw new RailsValidationException(errors);
        }

        SavedList list = findByLowerNameAndUser(name, userId);
        if (list != null) {
            list.setName(name);
            list.setUrl(url);
            list.setUser(userId == null ? null : entityManager.getReference(User.class, userId));
            list = savedListRepository.saveAndFlush(list);
        } else {
            list = new SavedList();
            list.setName(name);
            list.setUrl(url);
            list.setUser(userId == null ? null : entityManager.getReference(User.class, userId));
            list = savedListRepository.saveAndFlush(list);
        }
        return jsonWriter.writeOne(railsResources.savedList, list.getId());
    }

    /** {@code List.find(id).destroy} — no owner check (mirrored Rails gap). */
    @Transactional
    public void destroy(long id) {
        SavedList list = savedListRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("List " + id + " was not found"));
        savedListRepository.delete(list);
    }

    private SavedList findByLowerNameAndUser(String name, Long userId) {
        List<SavedList> matches = entityManager.createQuery("""
                SELECT l FROM SavedList l WHERE lower(l.name) = lower(:name)
                """, SavedList.class)
            .setParameter("name", name)
            .getResultList();
        return matches.stream()
            .filter(match -> {
                Long ownerId = match.getUser() == null ? null : match.getUser().getId();
                return userId == null ? ownerId == null : userId.equals(ownerId);
            })
            .findFirst()
            .orElse(null);
    }

    private static Long toLong(Integer id) {
        return id == null ? null : id.longValue();
    }
}
