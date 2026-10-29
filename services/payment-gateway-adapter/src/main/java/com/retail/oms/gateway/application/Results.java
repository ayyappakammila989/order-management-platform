package com.retail.oms.gateway.application;

import com.retail.oms.events.payment.GatewayCommand;
import com.retail.oms.events.payment.GatewayResult;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Builders for GatewayResult. */
public final class Results {

    public static GatewayResult.Builder of(GatewayCommand cmd, Outcome outcome, Clock clock) {
        return GatewayResult.newBuilder().setEventId(UUID.randomUUID().toString()).setOccurredAt(Instant.now(clock))
                .setRequestId(cmd.getRequestId()).setIdempotencyKey(cmd.getIdempotencyKey())
                .setOperation(cmd.getOperation()).setGateway(cmd.getGateway()).setOutcome(outcome.name())
                .setGatewayRef(cmd.getGatewayRef());
    }

    public static GatewayResult pendingRetry(GatewayCommand cmd, String why, Clock clock) {
        return of(cmd, Outcome.PENDING_RETRY, clock).setMessage(trim(why)).build();
    }

    /** A stored result answered again for a repeated command: new event id, the repeat's request id, flagged. */
    public static GatewayResult replay(GatewayResult stored, GatewayCommand cmd, Clock clock) {
        return GatewayResult.newBuilder(stored).setEventId(UUID.randomUUID().toString()).setOccurredAt(Instant.now(clock))
                .setRequestId(cmd.getRequestId()).setReplayed(true).build();
    }

    public static String trim(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300);
    }

    private Results() {
    }
}
