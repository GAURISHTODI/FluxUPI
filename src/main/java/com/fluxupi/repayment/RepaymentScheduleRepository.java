package com.fluxupi.repayment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepaymentScheduleRepository extends JpaRepository<RepaymentSchedule, UUID> {

    /**
     * Every finder eagerly loads {@code installments}. A schedule is never
     * useful without them — callers compute totals, allocate payments, and
     * render responses off the collection — so a lazy load here just means a
     * {@code LazyInitializationException} waiting to happen at the web layer.
     */
    @EntityGraph(attributePaths = "installments")
    List<RepaymentSchedule> findAllByCreditLineIdOrderByGeneratedAtDesc(UUID creditLineId);

    /**
     * The one schedule currently in force for a credit line, under a row lock.
     * Repayments take this lock so two concurrent payments cannot both allocate
     * against the same instalment.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "installments")
    @Query("""
            select s from RepaymentSchedule s
            where s.creditLine.id = :creditLineId
              and s.status in (com.fluxupi.repayment.RepaymentScheduleStatus.ACTIVE,
                               com.fluxupi.repayment.RepaymentScheduleStatus.DELINQUENT)
            """)
    Optional<RepaymentSchedule> findActiveForUpdate(@Param("creditLineId") UUID creditLineId);

    @EntityGraph(attributePaths = "installments")
    @Query("""
            select s from RepaymentSchedule s
            where s.creditLine.id = :creditLineId
              and s.status in (com.fluxupi.repayment.RepaymentScheduleStatus.ACTIVE,
                               com.fluxupi.repayment.RepaymentScheduleStatus.DELINQUENT)
            """)
    Optional<RepaymentSchedule> findActive(@Param("creditLineId") UUID creditLineId);
}
