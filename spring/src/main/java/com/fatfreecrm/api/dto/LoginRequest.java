package com.fatfreecrm.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Accepts a username or an email address, matching Devise's authentication keys. */
public record LoginRequest(@NotBlank String login, @NotBlank String password) {
}
