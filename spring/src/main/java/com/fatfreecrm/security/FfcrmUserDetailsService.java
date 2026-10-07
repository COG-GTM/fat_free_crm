package com.fatfreecrm.security;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import java.util.Locale;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FfcrmUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public FfcrmUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public FfcrmUserDetails loadUserByUsername(String login) {
        String normalizedLogin = login.strip().toLowerCase(Locale.ROOT);
        User user = userRepository.findByLogin(normalizedLogin, Limit.of(1)).stream()
            .findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("No user found for the supplied login"));
        return new FfcrmUserDetails(user);
    }
}
