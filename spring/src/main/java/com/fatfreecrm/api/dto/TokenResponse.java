package com.fatfreecrm.api.dto;

public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
}
