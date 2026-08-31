package com.fluxupi.lender;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LenderRepository extends JpaRepository<Lender, UUID> {

    Optional<Lender> findByCode(String code);

    List<Lender> findAllByActiveTrue();
}
