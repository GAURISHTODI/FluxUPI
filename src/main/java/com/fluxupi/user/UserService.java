package com.fluxupi.user;

import com.fluxupi.common.FluxUpiException;
import com.fluxupi.common.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User register(String fullName, String vpa, BigDecimal declaredMonthlyIncome) {
        User user = User.register(fullName, vpa, declaredMonthlyIncome);
        if (userRepository.existsByVpa(user.getVpa())) {
            throw new VpaAlreadyRegisteredException(user.getVpa());
        }
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    /** Raised when a mock VPA is already taken — mirrors a real VPA's uniqueness. */
    public static class VpaAlreadyRegisteredException extends FluxUpiException {
        public VpaAlreadyRegisteredException(String vpa) {
            super("VPA '%s' is already registered".formatted(vpa), HttpStatus.CONFLICT, "VPA_ALREADY_REGISTERED");
        }
    }
}
