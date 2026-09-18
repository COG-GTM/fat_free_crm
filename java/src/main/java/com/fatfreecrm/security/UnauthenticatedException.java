package com.fatfreecrm.security;

import org.springframework.security.core.AuthenticationException;

/** Raised when a request reaches code that needs a {@link CurrentUser} but none is present. */
public class UnauthenticatedException extends AuthenticationException {

    public UnauthenticatedException(String msg) {
        super(msg);
    }
}
