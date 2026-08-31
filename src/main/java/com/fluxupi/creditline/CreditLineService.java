package com.fluxupi.creditline;

import com.fluxupi.common.exception.ResourceNotFoundException;
import com.fluxupi.lender.Lender;
import com.fluxupi.lender.LenderRepository;
import com.fluxupi.underwriting.UnderwritingContext;
import com.fluxupi.underwriting.UnderwritingDecision;
import com.fluxupi.underwriting.UnderwritingEngine;
import com.fluxupi.user.User;
import com.fluxupi.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Application, underwriting and lifecycle for credit lines.
 *
 * <p>Underwriting runs synchronously at application time: a line is created
 * {@code PENDING}, the {@link UnderwritingEngine} decides, and the same call
 * either approves it with a sanctioned limit or rejects it — recording the full
 * rule-by-rule reasoning on the entity either way.
 */
@Service
public class CreditLineService {

    private static final Logger log = LoggerFactory.getLogger(CreditLineService.class);

    private final CreditLineRepository creditLineRepository;
    private final UserRepository userRepository;
    private final LenderRepository lenderRepository;
    private final UnderwritingEngine underwritingEngine;

    public CreditLineService(CreditLineRepository creditLineRepository,
                             UserRepository userRepository,
                             LenderRepository lenderRepository,
                             UnderwritingEngine underwritingEngine) {
        this.creditLineRepository = creditLineRepository;
        this.userRepository = userRepository;
        this.lenderRepository = lenderRepository;
        this.underwritingEngine = underwritingEngine;
    }

    @Transactional
    public CreditLine apply(UUID userId, String lenderCode) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        Lender lender = lenderRepository.findByCode(lenderCode)
                .orElseThrow(() -> new ResourceNotFoundException("Lender", lenderCode));

        CreditLine creditLine = CreditLine.apply(user, lender);
        creditLineRepository.save(creditLine);

        var exposure = creditLineRepository.sumExistingExposure(userId);
        UnderwritingDecision decision = underwritingEngine.evaluate(
                new UnderwritingContext(user, lender, exposure));

        if (decision.approved()) {
            creditLine.approve(decision.sanctionedLimit(), decision.summary());
            log.info("Credit line {} approved for {} at limit {}",
                    creditLine.getId(), user.getVpa(), decision.sanctionedLimit());
        } else {
            creditLine.reject(decision.summary());
            log.info("Credit line {} rejected for {}: {}",
                    creditLine.getId(), user.getVpa(), decision.failedRules());
        }
        return creditLineRepository.save(creditLine);
    }

    @Transactional
    public CreditLine activate(UUID creditLineId) {
        CreditLine creditLine = load(creditLineId);
        creditLine.activate();
        return creditLineRepository.save(creditLine);
    }

    @Transactional
    public CreditLine freeze(UUID creditLineId, String reason) {
        CreditLine creditLine = load(creditLineId);
        creditLine.freeze(reason);
        return creditLineRepository.save(creditLine);
    }

    @Transactional
    public CreditLine unfreeze(UUID creditLineId) {
        CreditLine creditLine = load(creditLineId);
        creditLine.unfreeze();
        return creditLineRepository.save(creditLine);
    }

    @Transactional
    public CreditLine close(UUID creditLineId, String reason) {
        CreditLine creditLine = load(creditLineId);
        creditLine.close(reason);
        return creditLineRepository.save(creditLine);
    }

    @Transactional(readOnly = true)
    public CreditLine get(UUID creditLineId) {
        return load(creditLineId);
    }

    @Transactional(readOnly = true)
    public List<CreditLine> forUser(UUID userId) {
        return creditLineRepository.findWithUserAndLenderByUserId(userId);
    }

    private CreditLine load(UUID creditLineId) {
        return creditLineRepository.findById(creditLineId)
                .orElseThrow(() -> new ResourceNotFoundException("CreditLine", creditLineId));
    }
}
