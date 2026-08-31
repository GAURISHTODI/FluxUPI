package com.fluxupi.transaction;

import com.fluxupi.AbstractIntegrationTest;
import com.fluxupi.TestDataFactory;
import com.fluxupi.common.Money;
import com.fluxupi.common.exception.InsufficientCreditLimitException;
import com.fluxupi.creditline.CreditLine;
import com.fluxupi.creditline.CreditLineRepository;
import com.fluxupi.ledger.LedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The correctness claim this project is built around: concurrent spends against
 * one credit line cannot overspend it, and the ledger stays balanced throughout.
 *
 * <p>Every test here uses a {@link CountDownLatch} to release all threads at
 * once. Firing them sequentially would let each one finish before the next
 * starts, which passes trivially and proves nothing — the bug being tested for
 * only appears when two threads read the same balance before either writes.
 */
class ConcurrentSpendIT extends AbstractIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private CreditLineRepository creditLineRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private TestDataFactory testData;

    @Test
    @DisplayName("two simultaneous spends that together exceed the limit: exactly one succeeds")
    void twoConcurrentSpendsCannotBothConsumeTheSameHeadroom() throws Exception {
        // ₹1,000 of headroom, two threads each trying to spend ₹700.
        CreditLine line = testData.persistActiveCreditLine(Money.of(1_000));

        List<Outcome> outcomes = runConcurrently(2, i -> transactionService.spend(new SpendCommand(
                line.getId(), Money.of(700), "merchant" + i + "@fluxbank", "concurrent spend " + i,
                UUID.randomUUID().toString())));

        long succeeded = outcomes.stream().filter(Outcome::ok).count();
        long refused = outcomes.stream()
                .filter(o -> o.error() instanceof InsufficientCreditLimitException)
                .count();

        assertThat(succeeded)
                .as("both spends succeeding would mean ₹1,400 drawn on a ₹1,000 line")
                .isEqualTo(1);
        assertThat(refused).isEqualTo(1);

        CreditLine reloaded = creditLineRepository.findById(line.getId()).orElseThrow();
        assertThat(reloaded.getAvailableLimit()).isEqualByComparingTo(Money.of(300));
        assertThat(ledgerService.reconcile().isBalanced()).isTrue();
    }

    @Test
    @DisplayName("many small concurrent spends draw the limit down to exactly zero, never past it")
    void concurrentSpendsDrainTheLimitExactlyAndNoFurther() throws Exception {
        // 40 threads each spending ₹100 against a ₹2,000 line: 20 must succeed.
        int threads = 40;
        BigDecimal each = Money.of(100);
        CreditLine line = testData.persistActiveCreditLine(Money.of(2_000));

        List<Outcome> outcomes = runConcurrently(threads, i -> transactionService.spend(new SpendCommand(
                line.getId(), each, "merchant@fluxbank", "drain " + i, UUID.randomUUID().toString())));

        long succeeded = outcomes.stream().filter(Outcome::ok).count();
        assertThat(succeeded).isEqualTo(20);
        assertThat(outcomes.stream().filter(o -> !o.ok()))
                .allSatisfy(o -> assertThat(o.error()).isInstanceOf(InsufficientCreditLimitException.class));

        CreditLine reloaded = creditLineRepository.findById(line.getId()).orElseThrow();
        assertThat(reloaded.getAvailableLimit()).isEqualByComparingTo(Money.ZERO);
        assertThat(reloaded.getUtilizedLimit()).isEqualByComparingTo(Money.of(2_000));
        assertThat(ledgerService.reconcile().isBalanced()).isTrue();
    }

    @Test
    @DisplayName("the same idempotency key sent from 8 threads at once charges the user once")
    void concurrentReplaysOfOneKeyProduceExactlyOneTransaction() throws Exception {
        CreditLine line = testData.persistActiveCreditLine(Money.of(10_000));
        String sharedKey = "race-" + UUID.randomUUID();
        SpendCommand command = new SpendCommand(line.getId(), Money.of(500),
                "merchant@fluxbank", "double-tap", sharedKey);

        List<Outcome> outcomes = runConcurrently(8, i -> transactionService.spend(command));

        assertThat(outcomes).allSatisfy(o ->
                assertThat(o.ok()).as("every caller should get a result, not an error: %s", o.error()).isTrue());

        // All eight callers see the same transaction id, and exactly one of them
        // did the work — the other seven were absorbed as replays.
        List<UUID> ids = outcomes.stream().map(o -> o.result().transaction().getId()).distinct().toList();
        assertThat(ids).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.result().replayed()).count()).isEqualTo(1);

        CreditLine reloaded = creditLineRepository.findById(line.getId()).orElseThrow();
        assertThat(reloaded.getAvailableLimit())
                .as("charged once, not eight times")
                .isEqualByComparingTo(Money.of(9_500));
        assertThat(ledgerService.reconcile().isBalanced()).isTrue();
    }

    @Test
    @DisplayName("spends and repayments interleaving on one line keep the books balanced")
    void mixedConcurrentSpendsAndRepaymentsStayConsistent() throws Exception {
        CreditLine line = testData.persistActiveCreditLine(Money.of(50_000));
        // Draw down first so there is something to repay.
        transactionService.spend(new SpendCommand(line.getId(), Money.of(20_000),
                "merchant@fluxbank", "opening draw", UUID.randomUUID().toString()));

        List<Outcome> outcomes = runConcurrently(20, i -> i % 2 == 0
                ? transactionService.spend(new SpendCommand(line.getId(), Money.of(500),
                        "merchant@fluxbank", "spend " + i, UUID.randomUUID().toString()))
                : transactionService.repay(line.getId(), Money.of(400), Money.of(50), Money.ZERO,
                        "repayment " + i, UUID.randomUUID().toString()));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.ok()).isTrue());

        CreditLine reloaded = creditLineRepository.findById(line.getId()).orElseThrow();
        // 10 spends of ₹500 out, 10 repayments of ₹400 principal back in.
        BigDecimal expectedAvailable = Money.of(50_000)
                .subtract(Money.of(20_000))
                .subtract(Money.of(5_000))
                .add(Money.of(4_000));
        assertThat(reloaded.getAvailableLimit()).isEqualByComparingTo(expectedAvailable);
        assertThat(ledgerService.reconcile().isBalanced()).isTrue();
    }

    // ------------------------------------------------------------------ harness

    private record Outcome(TransactionResult result, Throwable error) {
        boolean ok() {
            return error == null;
        }
    }

    /**
     * Runs {@code threads} copies of {@code work} and releases them all from a
     * single latch, so they contend for real rather than queueing politely.
     */
    private List<Outcome> runConcurrently(int threads, ThreadBody work) throws Exception {
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(threads);
        AtomicInteger index = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Outcome>> tasks = IntStream.range(0, threads)
                    .<Callable<Outcome>>mapToObj(ignored -> () -> {
                        int i = index.getAndIncrement();
                        ready.countDown();
                        startGun.await(10, TimeUnit.SECONDS);
                        try {
                            return new Outcome(work.run(i), null);
                        } catch (Throwable t) {
                            return new Outcome(null, t);
                        }
                    })
                    .toList();

            List<Future<Outcome>> futures = tasks.stream().map(pool::submit).toList();
            ready.await(10, TimeUnit.SECONDS);
            startGun.countDown();

            return futures.stream().map(f -> {
                try {
                    return f.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return new Outcome(null, e);
                }
            }).toList();
        }
    }

    @FunctionalInterface
    private interface ThreadBody {
        TransactionResult run(int index) throws Exception;
    }
}
