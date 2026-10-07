package com.fatfreecrm.security;

import java.io.Serializable;

public record AuthenticatedUser(Long id, String username, boolean admin) implements Serializable {

    private static final long serialVersionUID = 1L;
}
