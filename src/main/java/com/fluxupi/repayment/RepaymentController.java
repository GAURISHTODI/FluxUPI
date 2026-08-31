package com.fluxupi.repayment;

import com.fluxupi.transaction.TransactionResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping
@Tag(name = "Repayments", description = "EMI schedule generation and repayment")
public class RepaymentController {

    private final RepaymentService repaymentService;

    public RepaymentController(RepaymentService repaymentService) {
        this.repaymentService = repaymentService;
    }

    @PostMapping("/credit-lines/{id}/repayment-schedule")
    @Operation(summary = "Generate (or regenerate) the EMI schedule for the line's outstanding principal")
    public ResponseEntity<ScheduleResponse> generate(@PathVariable UUID id) {
        RepaymentSchedule schedule = repaymentService.generateSchedule(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(ScheduleResponse.from(schedule));
    }

    @GetMapping("/credit-lines/{id}/repayment-schedule")
    @Operation(summary = "Fetch the active EMI schedule")
    public ScheduleResponse current(@PathVariable UUID id) {
        return ScheduleResponse.from(repaymentService.currentSchedule(id));
    }

    @PostMapping("/repayments")
    @Operation(summary = "Make a repayment against a credit line's schedule. Idempotent.")
    public ResponseEntity<RepaymentResponse> repay(@Valid @RequestBody RepayRequest request,
                                                   @RequestHeader(value = "Idempotency-Key", required = false)
                                                   @Nullable String headerKey) {
        String key = firstNonBlank(request.idempotencyKey(), headerKey);
        TransactionResult result = request.amount() != null
                ? repaymentService.pay(request.creditLineId(), request.amount(), key)
                : repaymentService.payNextInstallment(request.creditLineId(), key);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(new RepaymentResponse(
                result.transaction().getId(), result.transaction().getAmount(), result.replayed()));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        throw new IllegalArgumentException("An idempotency key is required, in the body or the Idempotency-Key header");
    }

    public record RepayRequest(@NotNull UUID creditLineId,
                               @Nullable @Positive BigDecimal amount,
                               String idempotencyKey) {
    }

    public record RepaymentResponse(UUID transactionId, BigDecimal amount, boolean replayed) {
    }

    public record ScheduleResponse(UUID id, UUID creditLineId, String status, String interestStrategy,
                                   BigDecimal principal, BigDecimal totalInterest, BigDecimal totalPayable,
                                   BigDecimal outstanding, List<InstallmentResponse> installments) {
        static ScheduleResponse from(RepaymentSchedule s) {
            return new ScheduleResponse(s.getId(), s.getCreditLine().getId(), s.getStatus().name(),
                    s.getInterestStrategy().name(), s.getPrincipal(), s.getTotalInterest(), s.totalPayable(),
                    s.outstandingAmount(), s.getInstallments().stream().map(InstallmentResponse::from).toList());
        }
    }

    public record InstallmentResponse(int number, LocalDate dueDate, BigDecimal principalComponent,
                                      BigDecimal interestComponent, BigDecimal totalDue, BigDecimal paidAmount,
                                      String status) {
        static InstallmentResponse from(Installment i) {
            return new InstallmentResponse(i.getNumber(), i.getDueDate(), i.getPrincipalComponent(),
                    i.getInterestComponent(), i.totalDue(), i.getPaidAmount(), i.getStatus().name());
        }
    }
}
