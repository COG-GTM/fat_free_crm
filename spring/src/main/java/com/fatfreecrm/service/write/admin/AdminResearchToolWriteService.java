package com.fatfreecrm.service.write.admin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.ResearchTool;
import com.fatfreecrm.repository.ResearchToolRepository;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.write.RailsParams;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rails {@code Admin::ResearchToolsController} writes ({@code name, url_template, enabled}).
 * {@code ResearchTool} has no validations, so create/update always succeed. No PaperTrail.
 */
@Service
public class AdminResearchToolWriteService {

    private final ResearchToolRepository researchToolRepository;
    private final RailsJsonWriter jsonWriter;
    private final RailsResources railsResources;

    public AdminResearchToolWriteService(ResearchToolRepository researchToolRepository,
        RailsJsonWriter jsonWriter, RailsResources railsResources) {
        this.researchToolRepository = researchToolRepository;
        this.jsonWriter = jsonWriter;
        this.railsResources = railsResources;
    }

    @Transactional
    public ObjectNode create(RailsParams params) {
        ResearchTool tool = new ResearchTool();
        apply(tool, params);
        tool = researchToolRepository.saveAndFlush(tool);
        return jsonWriter.writeOne(railsResources.researchTool, tool.getId());
    }

    @Transactional
    public void update(long id, RailsParams params) {
        ResearchTool tool = find(id);
        apply(tool, params);
        researchToolRepository.saveAndFlush(tool);
    }

    @Transactional
    public void destroy(long id) {
        researchToolRepository.delete(find(id));
        researchToolRepository.flush();
    }

    private ResearchTool find(long id) {
        return researchToolRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("ResearchTool " + id + " was not found"));
    }

    private static void apply(ResearchTool tool, RailsParams params) {
        params.assignString("name", tool::setName);
        params.assignString("url_template", tool::setUrlTemplate);
        params.assignBoolean("enabled", value -> tool.setEnabled(Boolean.TRUE.equals(value)));
    }
}
