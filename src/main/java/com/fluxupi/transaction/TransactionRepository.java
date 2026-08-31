package com.fluxupi.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    List<Transaction> findAllByCreditLineIdOrderByCreatedAtAsc(UUID creditLineId);

    boolean existsByReversalOfId(UUID reversalOfId);

    /**
     * Principal drawn down and not yet reversed. Interest is not included —
     * that comes from the repayment schedule, not from the spend history.
     */
    @Query("""
            select coalesce(sum(t.amount), 0) from Transaction t
            where t.creditLine.id = :creditLineId
              and t.type = com.fluxupi.transaction.TransactionType.SPEND
              and t.status = com.fluxupi.transaction.TransactionStatus.SUCCESS
            """)
    BigDecimal sumSettledSpends(@Param("creditLineId") UUID creditLineId);

    @Query("""
            select coalesce(sum(t.amount), 0) from Transaction t
            where t.creditLine.id = :creditLineId
              and t.type = com.fluxupi.transaction.TransactionType.REPAYMENT
              and t.status = com.fluxupi.transaction.TransactionStatus.SUCCESS
            """)
    BigDecimal sumSettledRepayments(@Param("creditLineId") UUID creditLineId);
}
