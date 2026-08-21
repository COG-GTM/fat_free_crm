package com.fatfreecrm.security;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.GroupRepository;
import com.fatfreecrm.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rails allows sign-in by username or email (Devise
 * {@code authentication_keys}), so both are resolved here.
 */
@Service
public class CrmUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final GroupRepository groupRepository;

    public CrmUserDetailsService(UserRepository userRepository, GroupRepository groupRepository) {
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public CrmUserDetails loadUserByUsername(String login) {
        User user = userRepository.findByUsername(login)
            .or(() -> userRepository.findByEmailIgnoreCase(login))
            .orElseThrow(() -> new UsernameNotFoundException("No user for login " + login));
        return new CrmUserDetails(user, groupRepository.findGroupsForUser(user.getId()));
    }
}
