package com.fatfreecrm.api;

import com.fatfreecrm.api.dto.EntityMetadataResponse;
import com.fatfreecrm.service.read.MetadataService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/api/v1/metadata")
public class MetadataController {

    private final MetadataService metadataService;

    public MetadataController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @GetMapping("/{entity}")
    public EntityMetadataResponse metadata(@PathVariable String entity) {
        return metadataService.metadata(entity);
    }
}
