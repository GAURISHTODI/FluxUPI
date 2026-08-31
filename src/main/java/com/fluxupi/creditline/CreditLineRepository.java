package com.fluxupi.creditline;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditLineRepository extends JpaRepository<CreditLine, UUID> {

    /**
     * Reads pull in {@code user} and {@code lender} eagerly. Both are
     * {@code LAZY} on the entity for the write paths' sake, but every read that
     * feeds a REST response needs them, and a lazy load after the transaction
     * has closed is a {@code LazyInitializationException}.
     */
    @EntityGraph(attributePaths = {"user", "lender"})
    @Override
    Optional<CreditLine> findById(UUID id);

    @EntityGraph(attributePaths = {"user", "lender"})
    List<CreditLine> findWithUserAndLenderByUserId(UUID userId);

    /**
     * Loads a credit line under a {@code SELECT ... FOR UPDATE} row lock.
     *
     * <p>This is the linchpin of the concurrency story. Two simultaneous spends
     * on the same line both call this; the second blocks until the first
     * commits, so it reads the already-decremented {@code availableLimit} and
     * cannot overspend. Reading without this lock lets both see the same
     * balance and both succeed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CreditLine c where c.id = :id")
    Optional<CreditLine> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Total sanctioned credit a user already holds across all lenders, used by
     * the underwriting exposure rule. Only lines that represent real live
     * commitments count — rejected and closed ones do not.
     */
    @Query("""
            select coalesce(sum(c.approvedLimit), 0)
            from CreditLine c
            where c.user.id = :userId
              and c.status in (com.fluxupi.creditline.CreditLineStatus.APPROVED,
                               com.fluxupi.creditline.CreditLineStatus.ACTIVE,
                               com.fluxupi.creditline.CreditLineStatus.FROZEN)
            """)
    BigDecimal sumExistingExposure(@Param("userId") UUID userId);
}
