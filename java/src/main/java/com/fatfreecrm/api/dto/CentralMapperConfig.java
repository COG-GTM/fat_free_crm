package com.fatfreecrm.api.dto;

import org.mapstruct.MapperConfig;
import org.mapstruct.ReportingPolicy;

/**
 * Shared MapStruct settings; every mapper should declare
 * {@code @Mapper(config = CentralMapperConfig.class)}. Mappers become Spring beans and any
 * unmapped target property is a compile error, so a DTO can never silently drift from its
 * entity.
 */
@MapperConfig(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CentralMapperConfig {
}
