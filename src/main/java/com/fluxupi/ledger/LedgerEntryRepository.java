package com.fluxupi.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findAllByTransactionIdOrderByEntrySeqAsc(UUID transactionId);

    List<LedgerEntry> findAllByCreditLineIdOrderByCreatedAtAscEntrySeqAsc(UUID creditLineId);

    long countByTransactionId(UUID transactionId);

    /** Total of every debit ever written. Must equal {@link #totalCredits()}. */
    @Query("""
            select coalesce(sum(e.amount), 0) from LedgerEntry e
            where e.direction = com.fluxupi.ledger.EntryDirection.DEBIT
            """)
    BigDecimal totalDebits();

    @Query("""
            select coalesce(sum(e.amount), 0) from LedgerEntry e
            where e.direction = com.fluxupi.ledger.EntryDirection.CREDIT
            """)
    BigDecimal totalCredits();

    /**
     * Every transaction whose entries do not net to zero. The core assertion of
     * {@code LedgerReconciliationTest} is that this list is always empty; doing
     * the work in SQL means it stays fast at a million rows.
     */
    @Query("""
            select e.transactionId as transactionId,
                   sum(case when e.direction = com.fluxupi.ledger.EntryDirection.DEBIT
                            then e.amount else 0 end) as debits,
                   sum(case when e.direction = com.fluxupi.ledger.EntryDirection.CREDIT
                            then e.amount else 0 end) as credits
            from LedgerEntry e
            group by e.transactionId
            having sum(case when e.direction = com.fluxupi.ledger.EntryDirection.DEBIT
                            then e.amount else -e.amount end) <> 0
            """)
    List<TransactionImbalance> findImbalancedTransactions();

    /** Per-account net position, debits minus credits. */
    @Query("""
            select e.account as account,
                   sum(case when e.direction = com.fluxupi.ledger.EntryDirection.DEBIT
                            then e.amount else -e.amount end) as netDebit
            from LedgerEntry e
            group by e.account
            """)
    List<AccountBalance> findAccountBalances();

    /** Net movement on one account for one credit line — used by the statement endpoint. */
    @Query("""
            select coalesce(sum(case when e.direction = com.fluxupi.ledger.EntryDirection.DEBIT
                                     then e.amount else -e.amount end), 0)
            from LedgerEntry e
            where e.creditLineId = :creditLineId and e.account = :account
            """)
    BigDecimal netDebitFor(@Param("creditLineId") UUID creditLineId, @Param("account") LedgerAccount account);

    /** Any journal entry with fewer than two lines is malformed by definition. */
    @Query("""
            select e.transactionId from LedgerEntry e
            group by e.transactionId having count(e) < 2
            """)
    List<UUID> findTransactionsWithTooFewEntries();

    interface TransactionImbalance {
        UUID getTransactionId();

        BigDecimal getDebits();

        BigDecimal getCredits();
    }

    interface AccountBalance {
        LedgerAccount getAccount();

        BigDecimal getNetDebit();
    }
}
