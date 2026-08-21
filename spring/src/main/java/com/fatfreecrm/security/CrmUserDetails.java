package com.fatfreecrm.security;

import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.User;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * {@link UserDetails} view of a Rails user. Carries {@code password_salt}
 * because the legacy Devise hash cannot be verified without it.
 */
public class CrmUserDetails implements UserDetails {

    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String username;
    private final String email;
    private final String encryptedPassword;
    private final String passwordSalt;
    private final boolean active;
    @SuppressWarnings("serial")
    private final List<GrantedAuthority> authorities;

    public CrmUserDetails(User user, Collection<Group> groups) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.email = user.getEmail();
        this.encryptedPassword = user.getEncryptedPassword();
        this.passwordSalt = user.getPasswordSalt();
        this.active = user.isActive();
        List<GrantedAuthority> granted = new ArrayList<>();
        granted.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (user.isAdmin()) {
            granted.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        for (Group group : groups) {
            if (group.getName() != null) {
                granted.add(new SimpleGrantedAuthority("GROUP_" + group.getName()));
            }
        }
        this.authorities = List.copyOf(granted);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordSalt() {
        return passwordSalt;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return encryptedPassword;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return active;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
