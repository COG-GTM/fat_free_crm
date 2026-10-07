package com.fatfreecrm.security;

import com.fatfreecrm.domain.User;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class FfcrmUserDetails implements UserDetails, CredentialsContainer {

    private static final long serialVersionUID = 1L;

    private final Long userId;
    private final String username;
    private final boolean admin;
    private final boolean confirmed;
    private final boolean suspended;
    private final ArrayList<SimpleGrantedAuthority> authorities;
    private String password;

    public FfcrmUserDetails(User user) {
        userId = user.getId();
        username = user.getUsername();
        admin = user.isAdmin();
        confirmed = user.getConfirmedAt() != null;
        suspended = user.getSuspendedAt() != null;
        String encryptedPassword = user.getEncryptedPassword() == null ? "" : user.getEncryptedPassword();
        String salt = user.getPasswordSalt() == null ? "" : user.getPasswordSalt();
        password = "{authlogic-sha512}" + encryptedPassword + "$" + salt;
        authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (admin) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isAdmin() {
        return admin;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.copyOf(authorities);
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !suspended;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return confirmed;
    }

    @Override
    public void eraseCredentials() {
        password = null;
    }
}
