package com.retail.oms.fraud.api;

import com.retail.oms.fraud.api.dto.FraudDtos.CheckRequest;
import com.retail.oms.fraud.api.dto.FraudDtos.CheckResponse;
import com.retail.oms.fraud.application.FraudService;
import com.retail.oms.fraud.domain.FraudCase;
import com.retail.oms.fraud.domain.Subject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Architecture API table: POST /internal/v1/fraud/check "score an order or refund" (role SERVICE). */
@RestController
@RequestMapping("/internal/v1/fraud")
@Tag(name = "Fraud check (internal)")
public class FraudCheckController {

    private final FraudService fraud;

    public FraudCheckController(FraudService fraud) {
        this.fraud = fraud;
    }

    @PostMapping("/check")
    @Operation(summary = "Score now. REVIEW and REJECTED also open a case (GET /api/v1/fraud/cases).")
    public CheckResponse check(@Valid @RequestBody CheckRequest body) {
        var subject = new Subject(body.kind(), body.customerId(), body.amount(), body.currency(), body.shipToZip(),
                body.paymentToken(), body.lines() == null ? List.of()
                        : body.lines().stream().map(l -> new Subject.Line(l.sku(), l.quantity())).toList());
        var result = fraud.screenNow(subject, body.orderId(), body.referenceId());
        return CheckResponse.of(result.check(), result.fraudCase().map(FraudCase::getCaseId).orElse(null));
    }
}
