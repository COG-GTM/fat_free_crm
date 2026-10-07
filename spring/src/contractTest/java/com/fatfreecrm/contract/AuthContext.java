package com.fatfreecrm.contract;

import java.net.http.HttpClient;

public record AuthContext(HttpClient client, String authorization, String csrfToken, String note) {
}
