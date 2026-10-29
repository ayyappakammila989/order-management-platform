package com.retail.oms.fraud.api;

import com.retail.oms.common.web.CorrelationId;
import com.retail.oms.fraud.api.dto.FraudDtos.CaseResponse;
import com.retail.oms.fraud.api.dto.FraudDtos.DecisionRequest;
import com.retail.oms.fraud.application.FraudService;
import com.retail.oms.fraud.domain.CaseStatus;
import com.retail.oms.fraud.domain.FraudCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flagged cases (architecture API table: GET /api/v1/fraud/cases). Agents and supervisors see them; only a
 * supervisor decides (architecture 7.3: "approve flagged ...").
 */
@RestController
@RequestMapping("/api/v1/fraud/cases")
@Tag(name = "Fraud cases")
@Validated
public class FraudCaseController {

    private final FraudService fraud;

    public FraudCaseController(FraudService fraud) {
        this.fraud = fraud;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPPORT_AGENT', 'SUPPORT_SUPERVISOR')")
    @Operation(summary = "Cases by status, oldest first (default OPEN)")
    public List<CaseResponse> list(@RequestParam(defaultValue = "OPEN") CaseStatus status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return fraud.cases(status, limit).stream().map(this::response).toList();
    }

    @GetMapping("/{caseId}")
    @PreAuthorize("hasAnyRole('SUPPORT_AGENT', 'SUPPORT_SUPERVISOR')")
    public CaseResponse get(@PathVariable UUID caseId) {
        return response(fraud.fraudCase(caseId));
    }

    @PostMapping("/{caseId}/decision")
    @PreAuthorize("hasRole('SUPPORT_SUPERVISOR')")
    @Operation(summary = "APPROVE or REJECT an open case; the waiting order saga continues or compensates. 409 FRD-409 if not open.")
    public CaseResponse decide(@PathVariable UUID caseId, @Valid @RequestBody DecisionRequest body, Authentication auth) {
        FraudCase decided = fraud.decide(caseId, "APPROVE".equals(body.decision()), auth.getName(), body.note(),
                MDC.get(CorrelationId.MDC_KEY));
        return response(decided);
    }

    private CaseResponse response(FraudCase c) {
        return CaseResponse.of(c, fraud.check(c.getCheckId()));
    }
}
