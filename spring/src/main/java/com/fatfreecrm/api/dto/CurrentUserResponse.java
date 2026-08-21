package com.fatfreecrm.api.dto;

import java.util.List;

public record CurrentUserResponse(Long id, String username, String email, List<String> authorities) {

    public CurrentUserResponse(Long id, String username, String email, List<String> authorities) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.authorities = List.copyOf(authorities);
    }
}
