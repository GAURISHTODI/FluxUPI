package com.fluxupi;

import com.fluxupi.common.Money;
import com.fluxupi.creditline.CreditLine;
import com.fluxupi.creditline.CreditLineRepository;
import com.fluxupi.lender.Lender;
import com.fluxupi.lender.LenderRepository;
import com.fluxupi.user.User;
import com.fluxupi.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Persists the objects an integration test needs to get to the interesting
 * part. Deliberately goes through the domain model's own factory and transition
 * methods rather than raw SQL, so fixtures can never construct a state the
 * application itself could not reach.
 */
@Component
public class TestDataFactory {

    private final UserRepository userRepository;
    private final LenderRepository lenderRepository;
    private final CreditLineRepository creditLineRepository;

    public TestDataFactory(UserRepository userRepository,
                           LenderRepository lenderRepository,
                           CreditLineRepository creditLineRepository) {
        this.userRepository = userRepository;
        this.lenderRepository = lenderRepository;
        this.creditLineRepository = creditLineRepository;
    }

    @Transactional
    public User persistUser() {
        return userRepository.save(TestFixtures.user());
    }

    @Transactional
    public User persistUser(BigDecimal monthlyIncome) {
        return userRepository.save(TestFixtures.user(monthlyIncome));
    }

    @Transactional
    public Lender persistLender() {
        return lenderRepository.save(TestFixtures.lender());
    }

    @Transactional
    public Lender persistLender(Lender lender) {
        return lenderRepository.save(lender);
    }

    /** An ACTIVE credit line with the given limit, fully available. */
    @Transactional
    public CreditLine persistActiveCreditLine(BigDecimal limit) {
        CreditLine line = CreditLine.apply(persistUser(), persistLender());
        line.approve(limit, "test fixture");
        line.activate();
        return creditLineRepository.save(line);
    }

    @Transactional
    public CreditLine persistActiveCreditLine() {
        return persistActiveCreditLine(Money.of(100_000));
    }
}
