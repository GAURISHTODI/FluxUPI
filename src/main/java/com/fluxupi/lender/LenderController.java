package com.fluxupi.lender;

import com.fluxupi.repayment.InterestStrategyType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/lenders")
@Tag(name = "Lenders", description = "Simulated lending partners and their published parameters")
public class LenderController {

    private final LenderRepository lenderRepository;

    public LenderController(LenderRepository lenderRepository) {
        this.lenderRepository = lenderRepository;
    }

    @GetMapping
    @Operation(summary = "List lenders currently accepting applications")
    public List<LenderResponse> listActive() {
        return lenderRepository.findAllByActiveTrue().stream().map(LenderResponse::from).toList();
    }

    public record LenderResponse(String code, String displayName, BigDecimal minMonthlyIncome,
                                 BigDecimal maxCreditLimit, BigDecimal incomeMultiple,
                                 BigDecimal maxExposureMultiple, BigDecimal annualInterestRatePercent,
                                 InterestStrategyType interestStrategy, int defaultTenureMonths) {
        static LenderResponse from(Lender lender) {
            return new LenderResponse(lender.getCode(), lender.getDisplayName(), lender.getMinMonthlyIncome(),
                    lender.getMaxCreditLimit(), lender.getIncomeMultiple(), lender.getMaxExposureMultiple(),
                    lender.getAnnualInterestRatePercent(), lender.getInterestStrategy(),
                    lender.getDefaultTenureMonths());
        }
    }
}
