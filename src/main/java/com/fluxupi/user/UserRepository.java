package com.fluxupi.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByVpa(String vpa);

    boolean existsByVpa(String vpa);
}
