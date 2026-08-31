package com.fluxupi.transaction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/transactions")
@Tag(name = "Transactions", description = "Spends, reversals and their idempotent processing")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    @Operation(summary = "Spend against a credit line. Safe to retry with the same idempotency key.")
    public ResponseEntity<TransactionResponse> spend(@Valid @RequestBody SpendRequest request,
                                                     @RequestHeader(value = "Idempotency-Key", required = false)
                                                     @Nullable String headerKey) {
        String key = firstNonBlank(request.idempotencyKey(), headerKey);
        TransactionResult result = transactionService.spend(new SpendCommand(
                request.creditLineId(), request.amount(), request.payeeVpa(), request.description(), key));
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(TransactionResponse.from(result));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a transaction")
    public TransactionResponse get(@PathVariable UUID id) {
        return TransactionResponse.from(new TransactionResult(transactionService.findById(id), false));
    }

    @PostMapping("/{id}/reverse")
    @Operation(summary = "Reverse a successful spend. Idempotent.")
    public ResponseEntity<TransactionResponse> reverse(@PathVariable UUID id,
                                                       @Valid @RequestBody ReverseRequest request,
                                                       @RequestHeader(value = "Idempotency-Key", required = false)
                                                       @Nullable String headerKey) {
        String key = firstNonBlank(request.idempotencyKey(), headerKey);
        TransactionResult result = transactionService.reverse(id, request.reason(), key);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(TransactionResponse.from(result));
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

    public record SpendRequest(@NotNull UUID creditLineId,
                               @NotNull @Positive BigDecimal amount,
                               @NotBlank String payeeVpa,
                               String description,
                               String idempotencyKey) {
    }

    public record ReverseRequest(@NotBlank String reason, String idempotencyKey) {
    }

    public record TransactionResponse(UUID id, UUID creditLineId, String type, String status,
                                      BigDecimal amount, String payeeVpa, String description,
                                      UUID reversalOfId, boolean replayed, Instant createdAt, Instant completedAt) {
        static TransactionResponse from(TransactionResult result) {
            Transaction t = result.transaction();
            return new TransactionResponse(t.getId(), t.getCreditLine().getId(), t.getType().name(),
                    t.getStatus().name(), t.getAmount(), t.getPayeeVpa(), t.getDescription(),
                    t.getReversalOfId(), result.replayed(), t.getCreatedAt(), t.getCompletedAt());
        }
    }
}
