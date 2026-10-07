package com.fatfreecrm.contract;

import java.io.IOException;

public interface AuthAdapter {
    AuthContext authenticate(String userKey) throws IOException, InterruptedException;
}
