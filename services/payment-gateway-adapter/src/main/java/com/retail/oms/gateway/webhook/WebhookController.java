package com.retail.oms.gateway.webhook;

import com.retail.oms.common.web.OmsException;
import com.retail.oms.gateway.application.GatewayUnavailableException;
import com.retail.oms.gateway.config.GatewayProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /webhooks/stripe and /webhooks/paypal (architecture API table). No JWT: the gateway proves who it is with its
 * signature, plus the IP allow-list. The raw body is handed to the Camel route untouched (the signature is over
 * those exact bytes). 200 also for a duplicate, so the gateway stops sending it.
 */
@RestController
@RequestMapping("/webhooks")
@Tag(name = "Gateway webhooks")
public class WebhookController {

    private final ProducerTemplate camel;
    private final IpAllowList allowList;

    public WebhookController(ProducerTemplate camel, GatewayProperties props) {
        this.camel = camel;
        this.allowList = new IpAllowList(props.webhooks().allowedCidrs());
    }

    @PostMapping("/stripe")
    @Operation(summary = "Stripe event; Stripe-Signature header checked (HMAC-SHA256, 5 min tolerance)")
    public ResponseEntity<Map<String, Object>> stripe(@RequestBody byte[] body, @RequestHeader HttpHeaders headers,
            HttpServletRequest request) {
        return handle(WebhookRoutes.STRIPE, body, headers, request);
    }

    @PostMapping("/paypal")
    @Operation(summary = "PayPal event; verified with PayPal's verify-webhook-signature API")
    public ResponseEntity<Map<String, Object>> paypal(@RequestBody byte[] body, @RequestHeader HttpHeaders headers,
            HttpServletRequest request) {
        return handle(WebhookRoutes.PAYPAL, body, headers, request);
    }

    private ResponseEntity<Map<String, Object>> handle(String route, byte[] body, HttpHeaders headers,
            HttpServletRequest request) {
        if (!allowList.allows(request.getRemoteAddr())) {
            throw new WebhookForbiddenException(request.getRemoteAddr());
        }
        Map<String, String> lower = new TreeMap<>();
        headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        Exchange result = camel.send(route, e -> {
            e.setProperty(WebhookRoutes.RAW, body);
            e.setProperty(WebhookRoutes.HEADERS, lower);
        });
        Exception failure = result.getException();
        if (failure instanceof OmsException oms) {
            throw oms;
        }
        if (failure instanceof GatewayUnavailableException) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("received", false,
                    "error", "verification unavailable, retry later"));
        }
        if (failure != null) {
            throw new OmsException("WHK-503", HttpStatus.SERVICE_UNAVAILABLE, "Webhook could not be stored, retry later");
        }
        boolean duplicate = Boolean.TRUE.equals(result.getProperty(WebhookRoutes.DUPLICATE, Boolean.class));
        return ResponseEntity.ok(Map.of("received", true, "duplicate", duplicate));
    }
}
