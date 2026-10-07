package com.fatfreecrm.api.dto;

public record CurrentUserResponse(
    Long id,
    String username,
    String email,
    String firstName,
    String lastName,
    boolean admin
) {
}
