package com.fluxupi.creditline;

import com.fluxupi.repayment.InterestStrategyType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/credit-lines")
@Tag(name = "Credit lines", description = "Application, underwriting and lifecycle")
public class CreditLineController {

    private final CreditLineService creditLineService;
    private final StatementService statementService;

    public CreditLineController(CreditLineService creditLineService, StatementService statementService) {
        this.creditLineService = creditLineService;
        this.statementService = statementService;
    }

    @PostMapping
    @Operation(summary = "Apply for a credit line; underwriting runs synchronously")
    public ResponseEntity<CreditLineResponse> apply(@Valid @RequestBody ApplyRequest request,
                                                    UriComponentsBuilder uriBuilder) {
        CreditLine line = creditLineService.apply(request.userId(), request.lenderCode());
        var location = uriBuilder.path("/credit-lines/{id}").buildAndExpand(line.getId()).toUri();
        return ResponseEntity.created(location).body(CreditLineResponse.from(line));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a credit line")
    public CreditLineResponse get(@PathVariable UUID id) {
        return CreditLineResponse.from(creditLineService.get(id));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate an approved credit line so it can be spent")
    public CreditLineResponse activate(@PathVariable UUID id) {
        return CreditLineResponse.from(creditLineService.activate(id));
    }

    @PostMapping("/{id}/freeze")
    @Operation(summary = "Freeze an active credit line")
    public CreditLineResponse freeze(@PathVariable UUID id, @RequestParam(defaultValue = "manual freeze") String reason) {
        return CreditLineResponse.from(creditLineService.freeze(id, reason));
    }

    @PostMapping("/{id}/unfreeze")
    @Operation(summary = "Return a frozen credit line to active")
    public CreditLineResponse unfreeze(@PathVariable UUID id) {
        return CreditLineResponse.from(creditLineService.unfreeze(id));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close a credit line")
    public CreditLineResponse close(@PathVariable UUID id, @RequestParam(defaultValue = "closed by request") String reason) {
        return CreditLineResponse.from(creditLineService.close(id, reason));
    }

    @GetMapping("/{id}/statement")
    @Operation(summary = "Full statement: limits, transaction log, ledger view and active schedule")
    public StatementService.Statement statement(@PathVariable UUID id) {
        return statementService.forCreditLine(id);
    }

    public record ApplyRequest(@NotNull UUID userId, @NotBlank String lenderCode) {
    }

    public record CreditLineResponse(UUID id, UUID userId, String lenderCode, CreditLineStatus status,
                                     BigDecimal approvedLimit, BigDecimal availableLimit, BigDecimal utilizedLimit,
                                     BigDecimal annualInterestRatePercent, InterestStrategyType interestStrategy,
                                     int tenureMonths, String decisionReason, Instant createdAt) {
        static CreditLineResponse from(CreditLine line) {
            return new CreditLineResponse(line.getId(), line.getUser().getId(), line.getLender().getCode(),
                    line.getStatus(), line.getApprovedLimit(), line.getAvailableLimit(), line.getUtilizedLimit(),
                    line.getAnnualInterestRatePercent(), line.getInterestStrategy(), line.getTenureMonths(),
                    line.getDecisionReason(), line.getCreatedAt());
        }
    }
}
