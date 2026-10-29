package com.retail.oms.fraud.api.dto;

import com.retail.oms.fraud.domain.CheckKind;
import com.retail.oms.fraud.domain.FraudCase;
import com.retail.oms.fraud.domain.FraudCheck;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FraudDtos {

    @Schema(description = "Screen an order or a refund now (no saga involved)")
    public record CheckRequest(
            @NotNull CheckKind kind,
            @NotNull UUID orderId,
            @Size(max = 64) @Schema(description = "refund or return id for kind REFUND") String referenceId,
            @NotBlank @Size(max = 64) String customerId,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @Pattern(regexp = "[0-9]{5}") String shipToZip,
            @Size(max = 100) String paymentToken,
            @Size(max = 100) List<@Valid @NotNull Line> lines) {

        public record Line(@NotBlank @Size(max = 40) String sku, @Min(1) int quantity) {
        }
    }

    public record CheckResponse(UUID checkId, String decision, int score, List<String> reasons, UUID caseId) {

        public static CheckResponse of(FraudCheck check, UUID caseId) {
            return new CheckResponse(check.getCheckId(), check.effectiveDecision().name(), check.getScore(),
                    check.reasonList(), caseId);
        }
    }

    public record DecisionRequest(
            @NotNull @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(max = 500) @Schema(description = "why; kept on the case") String note) {
    }

    public record CaseResponse(UUID caseId, UUID orderId, String kind, String customerId, BigDecimal amount,
                               String currency, int score, List<String> reasons, String status, Instant openedAt,
                               String decidedBy, Instant decidedAt, String note) {

        public static CaseResponse of(FraudCase c, FraudCheck check) {
            return new CaseResponse(c.getCaseId(), c.getOrderId(), check.getKind().name(), check.getCustomerId(),
                    check.getAmount(), check.getCurrency(), check.getScore(), check.reasonList(), c.getStatus().name(),
                    c.getOpenedAt(), c.getDecidedBy(), c.getDecidedAt(), c.getNote());
        }
    }

    private FraudDtos() {
    }
}
