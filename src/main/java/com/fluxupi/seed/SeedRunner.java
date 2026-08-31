package com.fluxupi.seed;

import com.fluxupi.common.Money;
import com.fluxupi.creditline.CreditLine;
import com.fluxupi.creditline.CreditLineService;
import com.fluxupi.ledger.LedgerService;
import com.fluxupi.repayment.RepaymentService;
import com.fluxupi.transaction.SpendCommand;
import com.fluxupi.transaction.TransactionService;
import com.fluxupi.user.User;
import com.fluxupi.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Populates a running instance with a realistic body of data and prints a
 * ledger-reconciliation report at the end.
 *
 * <p>Enabled only under the {@code seed} profile:
 * <pre>
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=seed
 * </pre>
 * or {@code java -jar target/fluxupi-0.1.0.jar --spring.profiles.active=seed}.
 *
 * <p>It drives the same public services the REST API uses — nothing here
 * reaches around them — so the seed is also an end-to-end smoke test of the
 * whole stack against a real database. The RNG is fixed-seed so a run is
 * reproducible.
 */
@Component
@Profile("seed")
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);
    private static final int USERS = 25;
    private static final int MIN_TRANSACTIONS = 1_200;

    private final UserService userService;
    private final CreditLineService creditLineService;
    private final TransactionService transactionService;
    private final RepaymentService repaymentService;
    private final LedgerService ledgerService;

    public SeedRunner(UserService userService,
                      CreditLineService creditLineService,
                      TransactionService transactionService,
                      RepaymentService repaymentService,
                      LedgerService ledgerService) {
        this.userService = userService;
        this.creditLineService = creditLineService;
        this.transactionService = transactionService;
        this.repaymentService = repaymentService;
        this.ledgerService = ledgerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Random random = new Random(42);
        String[] lenderCodes = {"QUICKCASH", "PRUDENT", "STARTER"};

        List<CreditLine> activeLines = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            BigDecimal income = Money.of(12_000 + random.nextInt(90_000));
            User user = userService.register("Seed User " + i,
                    "seed.user." + i + "." + UUID.randomUUID() + "@fluxbank", income);

            for (String code : lenderCodes) {
                CreditLine line = creditLineService.apply(user.getId(), code);
                if (line.getStatus().name().equals("APPROVED")) {
                    activeLines.add(creditLineService.activate(line.getId()));
                }
            }
        }
        log.info("Seed: {} users created, {} credit lines active", USERS, activeLines.size());

        if (activeLines.isEmpty()) {
            log.warn("Seed: no credit lines were approved; nothing to transact against");
            return;
        }

        int spends = 0;
        int repayments = 0;
        int reversals = 0;
        int refused = 0;

        for (int i = 0; i < MIN_TRANSACTIONS; i++) {
            CreditLine line = activeLines.get(random.nextInt(activeLines.size()));
            int roll = random.nextInt(100);
            try {
                if (roll < 70) {
                    BigDecimal amount = Money.of(1 + random.nextInt(2_000));
                    transactionService.spend(new SpendCommand(line.getId(), amount,
                            "merchant" + random.nextInt(50) + "@fluxbank", "seed spend", UUID.randomUUID().toString()));
                    spends++;
                } else if (roll < 85) {
                    var result = transactionService.spend(new SpendCommand(line.getId(),
                            Money.of(1 + random.nextInt(1_500)), "merchant@fluxbank", "seed spend to reverse",
                            UUID.randomUUID().toString()));
                    transactionService.reverse(result.transaction().getId(), "seed reversal",
                            UUID.randomUUID().toString());
                    reversals++;
                    spends++;
                } else {
                    try {
                        repaymentService.generateSchedule(line.getId());
                        repaymentService.payNextInstallment(line.getId(), UUID.randomUUID().toString());
                        repayments++;
                    } catch (RuntimeException noPrincipal) {
                        // Nothing drawn on this line yet — fine, skip.
                    }
                }
            } catch (com.fluxupi.common.exception.InsufficientCreditLimitException starved) {
                refused++;
            }
        }

        LedgerService.ReconciliationReport report = ledgerService.reconcile();
        log.info("""
                Seed complete.
                  spends={}  reversals={}  repayments={}  refused={}
                  ledger entries={}  debits={}  credits={}  balanced={}
                """,
                spends, reversals, repayments, refused,
                report.entryCount(), report.totalDebits(), report.totalCredits(), report.isBalanced());

        if (!report.isBalanced()) {
            throw new IllegalStateException("Seed produced an unbalanced ledger: " + report.difference());
        }
    }
}
