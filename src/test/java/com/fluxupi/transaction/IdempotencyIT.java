package com.fluxupi.transaction;

import com.fluxupi.AbstractIntegrationTest;
import com.fluxupi.TestDataFactory;
import com.fluxupi.common.Money;
import com.fluxupi.common.exception.DuplicateIdempotencyKeyException;
import com.fluxupi.common.exception.IllegalStateTransitionException;
import com.fluxupi.common.exception.InsufficientCreditLimitException;
import com.fluxupi.creditline.CreditLine;
import com.fluxupi.creditline.CreditLineRepository;
import com.fluxupi.ledger.LedgerEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The non-concurrent half of the idempotency contract. The racing case lives in
 * {@link ConcurrentSpendIT}.
 */
class IdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private CreditLineRepository creditLineRepository;

    @Autowired
    private TestDataFactory testData;

    @Test
    @DisplayName("replaying the identical request returns the original result and moves no money")
    void identicalRetryIsAbsorbed() {
        CreditLine line = testData.persistActiveCreditLine(Money.of(10_000));
        SpendCommand command = new SpendCommand(line.getId(), Money.of(2_000),
                "shop@fluxbank", "groceries", "key-" + UUID.randomUUID());

        TransactionResult first = transactionService.spend(command);
        TransactionResult second = transactionService.spend(command);
        TransactionResult third = transactionService.spend(command);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(third.replayed()).isTrue();
        assertThat(second.transaction().getId()).isEqualTo(first.transaction().getId());
        assertThat(third.transaction().getId()).isEqualTo(first.transaction().getId());

        assertThat(creditLineRepository.findById(line.getId()).orElseThrow().getAvailableLimit())
                .as("three requests, one charge")
                .isEqualByComparingTo(Money.of(8_000));
        assertThat(ledgerEntryRepository.countByTransactionId(first.transaction().getId()))
                .as("a replay must not append more ledger entries")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("reusing a key with a different amount is rejected, not silently absorbed")
    void keyReuseWithADifferentPayloadIsRejected() {
        CreditLine line = testData.persistActiveCreditLine(Money.of(10_000));
        String key = "key-" + UUID.randomUUID();

        transactionService.spend(new SpendCommand(line.getId(), Money.of(2_000),
                "shop@fluxbank", "groceries", key));

        assertThatThrownBy(() -> transactionService.spend(new SpendCommand(line.getId(), Money.of(9_000),
                "shop@fluxbank", "groceries", key)))
                .isInstanceOf(DuplicateIdempotencyKeyException.class)
                .hasMessageContaining(key);

        assertThat(creditLineRepository.findById(line.getId()).orElseThrow().getAvailableLimit())
                .isEqualByComparingTo(Money.of(8_000));
    }

    @Test
    @DisplayName("a different payee with the same key is also a conflict")
    void keyReuseWithADifferentPayeeIsRejected() {
        CreditLine line = testData.persistActiveCreditLine(Money.of(10_000));
        String key = "key-" + UUID.randomUUID();

        transactionService.spend(new SpendCommand(line.getId(), Money.of(500),
                "honest.shop@fluxbank", "coffee", key));

        assertThatThrownBy(() -> transactionService.spend(new SpendCommand(line.getId(), Money.of(500),
                "attacker@fluxbank", "coffee", key)))
                .isInstanceOf(DuplicateIdempotencyKeyException.class);
    }

    @Test
    @DisplayName("a refused spend is recorded as FAILED and writes no ledger entries")
    void refusedSpendLeavesAnAuditTrailButNoPostings() {
        CreditLine line = testData.persistActiveCreditLine(Money.of(1_000));
        String key = "key-" + UUID.randomUUID();

        assertThatThrownBy(() -> transactionService.spend(new SpendCommand(line.getId(), Money.of(5_000),
                "shop@fluxbank", "too big", key)))
                .isInstanceOf(InsufficientCreditLimitException.class);

        Transaction failed = transactionRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(failed.getFailureReason()).contains("requested");
        assertThat(ledgerEntryRepository.countByTransactionId(failed.getId()))
                .as("a refused spend must not touch the books")
                .isZero();

        assertThat(creditLineRepository.findById(line.getId()).orElseThrow().getAvailableLimit())
                .isEqualByComparingTo(Money.of(1_000));
    }

    @Test
    @DisplayName("a reversal restores the limit and can only happen once")
    void reversalIsAppliedOnceAndOnlyOnce() {
        CreditLine line = testData.persistActiveCreditLine(Money.of(10_000));
        TransactionResult spend = transactionService.spend(new SpendCommand(line.getId(), Money.of(2_500),
                "shop@fluxbank", "returned item", "key-" + UUID.randomUUID()));

        transactionService.reverse(spend.transaction().getId(), "item returned", "rev-" + UUID.randomUUID());

        assertThat(creditLineRepository.findById(line.getId()).orElseThrow().getAvailableLimit())
                .isEqualByComparingTo(Money.of(10_000));
        assertThat(transactionRepository.findById(spend.transaction().getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.REVERSED);

        // A second reversal under a fresh key must be refused by the state machine.
        assertThatThrownBy(() -> transactionService.reverse(spend.transaction().getId(),
                "double refund attempt", "rev-" + UUID.randomUUID()))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(creditLineRepository.findById(line.getId()).orElseThrow().getAvailableLimit())
                .as("the limit must not be credited twice")
                .isEqualByComparingTo(Money.of(10_000));
    }

    @Test
    @DisplayName("replaying a reversal key returns the stored reversal")
    void reversalIsItselfIdempotent() {
        CreditLine line = testData.persistActiveCreditLine(Money.of(10_000));
        TransactionResult spend = transactionService.spend(new SpendCommand(line.getId(), Money.of(1_000),
                "shop@fluxbank", "item", "key-" + UUID.randomUUID()));
        String reversalKey = "rev-" + UUID.randomUUID();

        TransactionResult first = transactionService.reverse(spend.transaction().getId(), "returned", reversalKey);
        TransactionResult replay = transactionService.reverse(spend.transaction().getId(), "returned", reversalKey);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transaction().getId()).isEqualTo(first.transaction().getId());
        assertThat(creditLineRepository.findById(line.getId()).orElseThrow().getAvailableLimit())
                .isEqualByComparingTo(Money.of(10_000));
    }

    @Test
    @DisplayName("spending on a frozen line is refused")
    void frozenLineCannotSpend() {
        CreditLine line = testData.persistActiveCreditLine(Money.of(10_000));
        CreditLine managed = creditLineRepository.findById(line.getId()).orElseThrow();
        managed.freeze("risk review");
        creditLineRepository.save(managed);

        assertThatThrownBy(() -> transactionService.spend(new SpendCommand(line.getId(), Money.of(100),
                "shop@fluxbank", "blocked", "key-" + UUID.randomUUID())))
                .isInstanceOf(IllegalStateTransitionException.class);
    }
}
