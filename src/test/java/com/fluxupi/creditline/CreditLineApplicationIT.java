package com.fluxupi.creditline;

import com.fluxupi.AbstractIntegrationTest;
import com.fluxupi.common.Money;
import com.fluxupi.common.exception.IllegalStateTransitionException;
import com.fluxupi.lender.Lender;
import com.fluxupi.lender.LenderRepository;
import com.fluxupi.user.User;
import com.fluxupi.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Application + underwriting end to end against a real database, including the
 * exposure rule which depends on a SQL aggregate over existing credit lines.
 */
class CreditLineApplicationIT extends AbstractIntegrationTest {

    @Autowired
    private CreditLineService creditLineService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LenderRepository lenderRepository;

    private Lender lender(String code, BigDecimal minIncome, BigDecimal maxLimit,
                          BigDecimal incomeMultiple, BigDecimal exposureMultiple) {
        return lenderRepository.save(com.fluxupi.TestFixtures.lenderBuilder()
                .code(code)
                .minMonthlyIncome(minIncome)
                .maxCreditLimit(maxLimit)
                .incomeMultiple(incomeMultiple)
                .maxExposureMultiple(exposureMultiple)
                .build());
    }

    private User user(BigDecimal income) {
        return userRepository.save(com.fluxupi.TestFixtures.user(income));
    }

    @Test
    @DisplayName("a qualifying applicant is approved with the sanctioned limit and a recorded reason")
    void approvedApplicantGetsALimitAndAReason() {
        Lender lender = lender("APPR", Money.of(20_000), Money.of(300_000),
                new BigDecimal("2.00"), new BigDecimal("3.00"));
        User user = user(Money.of(50_000));

        CreditLine line = creditLineService.apply(user.getId(), lender.getCode());

        assertThat(line.getStatus()).isEqualTo(CreditLineStatus.APPROVED);
        assertThat(line.getApprovedLimit()).isEqualByComparingTo(Money.of(100_000));
        assertThat(line.getAvailableLimit()).isEqualByComparingTo(Money.of(100_000));
        assertThat(line.getDecisionReason()).contains("APPROVED", "minimum-income", "existing-exposure");
    }

    @Test
    @DisplayName("a low-income applicant is rejected and the failing rule is on the record")
    void lowIncomeApplicantRejected() {
        Lender lender = lender("REJ", Money.of(40_000), Money.of(300_000),
                new BigDecimal("2.00"), new BigDecimal("3.00"));
        User user = user(Money.of(18_000));

        CreditLine line = creditLineService.apply(user.getId(), lender.getCode());

        assertThat(line.getStatus()).isEqualTo(CreditLineStatus.REJECTED);
        assertThat(line.getApprovedLimit()).isEqualByComparingTo(Money.ZERO);
        assertThat(line.getDecisionReason()).contains("REJECTED", "minimum-income");

        assertThatThrownBy(() -> creditLineService.activate(line.getId()))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    @DisplayName("a second application is capped by the exposure already sanctioned on the first")
    void secondApplicationIsCappedByExistingExposure() {
        User user = user(Money.of(40_000));
        Lender first = lender("EXP1", Money.of(10_000), Money.of(500_000),
                new BigDecimal("3.00"), new BigDecimal("3.00"));
        Lender second = lender("EXP2", Money.of(10_000), Money.of(500_000),
                new BigDecimal("3.00"), new BigDecimal("3.00"));

        // First line: min(120k affordability, 500k max, 120k exposure ceiling) = 120k.
        CreditLine line1 = creditLineService.apply(user.getId(), first.getCode());
        assertThat(line1.getApprovedLimit()).isEqualByComparingTo(Money.of(120_000));

        // Ceiling is 3 x 40,000 = 120,000, already fully used -> second is rejected.
        CreditLine line2 = creditLineService.apply(user.getId(), second.getCode());
        assertThat(line2.getStatus()).isEqualTo(CreditLineStatus.REJECTED);
        assertThat(line2.getDecisionReason()).contains("existing-exposure");
    }

    @Test
    @DisplayName("a rejected line does not count towards exposure, so a later application can still pass")
    void rejectedLinesDoNotConsumeExposure() {
        User user = user(Money.of(30_000));
        Lender strict = lender("STRICT", Money.of(50_000), Money.of(200_000),
                new BigDecimal("2.00"), new BigDecimal("3.00"));
        Lender lenient = lender("LENIENT", Money.of(10_000), Money.of(200_000),
                new BigDecimal("2.00"), new BigDecimal("3.00"));

        CreditLine rejected = creditLineService.apply(user.getId(), strict.getCode());
        assertThat(rejected.getStatus()).isEqualTo(CreditLineStatus.REJECTED);

        CreditLine approved = creditLineService.apply(user.getId(), lenient.getCode());
        assertThat(approved.getStatus()).isEqualTo(CreditLineStatus.APPROVED);
        assertThat(approved.getApprovedLimit()).isEqualByComparingTo(Money.of(60_000));
    }
}
