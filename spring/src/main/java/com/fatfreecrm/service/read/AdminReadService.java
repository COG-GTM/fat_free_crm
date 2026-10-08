package com.fatfreecrm.service.read;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.support.RailsYaml;
import com.fatfreecrm.repository.ResearchToolRepository;
import com.fatfreecrm.repository.TagRepository;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResources;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this service."
)
public class AdminReadService {

    private final RailsJsonWriter jsonWriter;
    private final RailsResources resources;
    private final TagRepository tagRepository;
    private final ResearchToolRepository researchToolRepository;
    private final ObjectMapper objectMapper;

    public AdminReadService(
        RailsJsonWriter jsonWriter,
        RailsResources resources,
        TagRepository tagRepository,
        ResearchToolRepository researchToolRepository,
        ObjectMapper objectMapper
    ) {
        this.jsonWriter = jsonWriter;
        this.resources = resources;
        this.tagRepository = tagRepository;
        this.researchToolRepository = researchToolRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ObjectNode group(long id) {
        return jsonWriter.writeOne(resources.group, id);
    }

    @Transactional(readOnly = true)
    public ObjectNode field(long id) {
        ObjectNode result = jsonWriter.writeOne(resources.field, id);
        result.set("settings", objectMapper.valueToTree(RailsYaml.readStringMap(
            result.path("settings").isTextual() ? result.path("settings").asText() : null)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<ObjectNode> tags() {
        List<Long> ids = tagRepository.findIdsInIdOrder();
        return jsonWriter.write(resources.tag, ids);
    }

    @Transactional(readOnly = true)
    public List<ObjectNode> researchTools() {
        List<Long> ids = researchToolRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
            .map(tool -> tool.getId())
            .toList();
        return jsonWriter.write(resources.researchTool, ids);
    }
}
