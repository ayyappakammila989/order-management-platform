package com.retail.oms.gateway.routes;

import com.retail.oms.events.EventHeaders;
import com.retail.oms.events.Topics;
import com.retail.oms.events.payment.GatewayCommand;
import com.retail.oms.events.payment.GatewayResult;
import com.retail.oms.gateway.application.AvroBytes;
import com.retail.oms.gateway.application.HttpCall;
import com.retail.oms.gateway.application.IdempotencyStore;
import com.retail.oms.gateway.application.Outcome;
import com.retail.oms.gateway.application.Results;
import com.retail.oms.gateway.config.GatewayProperties;
import com.retail.oms.gateway.config.ResilienceConfig;
import com.retail.oms.gateway.paypal.PayPalApi;
import com.retail.oms.gateway.stripe.StripeApi;
import com.retail.oms.kafka.AvroEventDecoder;
import com.retail.oms.kafka.PoisonPillException;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.kafka.consumer.KafkaManualCommit;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Architecture 5, Camel routes stripe-payment / paypal-payment:
 * <pre>
 * kafka oms.payment.gateway-cmd --> decode --> claim pay:{key} in Redis --+--> stored result? replay it
 *                                                                         +--> direct:stripe | direct:paypal
 *                                       circuit breaker + 10 s time limiter around the HTTP call
 *                                       fallback: PENDING_RETRY (payment-service retries with the SAME key)
 * --> keep final result in Redis (24 h) --> kafka oms.payment.gateway-result --> commit the offset
 * </pre>
 * The offset is committed only after the result is in Kafka (at-least-once). Infrastructure errors (Redis, Schema
 * Registry, Kafka) are redelivered with backoff and, if that is not enough, the record is polled again: it stays in
 * Kafka. Bytes that can never be decoded go to the dead-letter topic.
 */
@Component
public class GatewayRoutes extends RouteBuilder {

    static final String CMD = "omsCommand";
    static final String RESULT = "omsResult";
    static final String COMMIT = "omsCommit";
    static final String ORIGINAL = "omsOriginalBytes";
    static final String CORRELATION = "omsCorrelationId";
    static final String REPLAYED = "omsReplayed";
    static final String TRACE = "omsCallTrace";
    static final Set<String> OPERATIONS = Set.of("AUTHORIZE", "CAPTURE", "VOID", "REFUND");
    /** The real URL comes in the CamelHttpUri header; timeouts are set on the component (application.yml). */
    static final String HTTP = "http://gateway?throwExceptionOnFailure=false";

    private static final Logger log = LoggerFactory.getLogger(GatewayRoutes.class);

    private final AvroEventDecoder decoder;
    private final AvroBytes avro;
    private final IdempotencyStore idempotency;
    private final StripeApi stripe;
    private final PayPalApi paypal;
    private final GatewayProperties props;
    private final MeterRegistry meters;
    private final Clock clock;

    public GatewayRoutes(AvroEventDecoder decoder, AvroBytes avro, IdempotencyStore idempotency, StripeApi stripe,
            PayPalApi paypal, GatewayProperties props, MeterRegistry meters, Clock clock) {
        this.decoder = decoder;
        this.avro = avro;
        this.idempotency = idempotency;
        this.stripe = stripe;
        this.paypal = paypal;
        this.props = props;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public void configure() {
        String commands = "kafka:" + Topics.PAYMENT_GATEWAY_CMD + "?groupId=payment-gateway-adapter&autoOffsetReset=earliest"
                + "&valueDeserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer"
                + "&autoCommitEnable=false&allowManualCommit=true&breakOnFirstError=true&maxPollRecords=20"
                + "&consumersCount=" + props.consumers();
        String bytesOut = "?valueSerializer=org.apache.kafka.common.serialization.ByteArraySerializer";
        int timeoutMs = (int) props.callTimeout().toMillis();

        errorHandler(defaultErrorHandler().maximumRedeliveries(5).redeliveryDelay(1000).useExponentialBackOff()
                .backOffMultiplier(2).maximumRedeliveryDelay(16000).retryAttemptedLogLevel(org.apache.camel.LoggingLevel.WARN));

        onException(PoisonPillException.class).handled(true)
                .process(this::toDeadLetter)
                .to("kafka:" + props.dlqTopic() + bytesOut)
                .process(this::commit);

        from(commands).routeId("gateway-cmd-in")
                .process(this::decode)
                .process(this::claim)
                .choice()
                    .when(exchangeProperty(RESULT).isNull())
                        .choice()
                            .when(simple("${exchangeProperty." + CMD + ".gateway} == 'STRIPE'")).to("direct:stripe")
                            .otherwise().to("direct:paypal")
                        .end()
                        .process(this::complete)
                .end()
                .process(this::toResultMessage)
                .to("kafka:" + Topics.PAYMENT_GATEWAY_RESULT + bytesOut)
                .process(this::commit);

        from("direct:gateway-http").routeId("gateway-http").errorHandler(noErrorHandler())
                .doTry()
                    .to(HTTP)
                .doCatch(Exception.class)
                    .process(GatewayRoutes::recordAndRethrow)
                .end();

        // no error handler here: a failed call must not be redelivered behind the breaker's back (6.4: no automatic
        // retry); the breaker counts it and the fallback answers PENDING_RETRY
        from("direct:stripe").routeId("stripe-payment").errorHandler(noErrorHandler())
                .process(e -> e.setProperty(TRACE, new CallTrace()))
                .circuitBreaker()
                    .resilience4jConfiguration().circuitBreaker(ResilienceConfig.STRIPE)
                        .timeoutEnabled(true).timeoutDuration(timeoutMs).end()
                    .process(e -> {
                        trace(e).started = true;
                        trace(e).run(() -> prepare(e, stripe.request(cmd(e))));
                    })
                    .to("direct:gateway-http")
                    .process(e -> trace(e).run(() -> e.setProperty(RESULT, stripe.response(cmd(e), status(e), body(e)))))
                .onFallback()
                    .process(this::fallback)
                .end();

        // no error handler here: a failed call must not be redelivered behind the breaker's back (6.4: no automatic
        // retry); the breaker counts it and the fallback answers PENDING_RETRY
        from("direct:paypal").routeId("paypal-payment").errorHandler(noErrorHandler())
                .process(e -> e.setProperty(TRACE, new CallTrace()))
                .circuitBreaker()
                    .resilience4jConfiguration().circuitBreaker(ResilienceConfig.PAYPAL)
                        .timeoutEnabled(true).timeoutDuration(timeoutMs).end()
                    .process(e -> {
                        trace(e).started = true;
                        trace(e).run(() -> prepare(e, paypal.request(cmd(e))));
                    })
                    .to("direct:gateway-http")
                    .process(e -> trace(e).run(() -> e.setProperty(RESULT, paypal.response(cmd(e), status(e), body(e)))))
                .onFallback()
                    .process(this::fallback)
                .end();
    }

    /** Raw bytes -> GatewayCommand. A command that decodes but cannot be sent is answered ERROR (not dead-lettered). */
    void decode(Exchange e) {
        byte[] bytes = e.getMessage().getBody(byte[].class);
        e.setProperty(ORIGINAL, bytes);
        e.setProperty(COMMIT, e.getMessage().getHeader(KafkaConstants.MANUAL_COMMIT, KafkaManualCommit.class));
        e.setProperty(CORRELATION, e.getMessage().getHeader(EventHeaders.CORRELATION_ID, String.class));
        Headers headers = e.getMessage().getHeader(KafkaConstants.HEADERS, Headers.class);
        if (headers == null) {
            headers = new org.apache.kafka.common.header.internals.RecordHeaders();
        }
        Object message = decoder.decode(Topics.PAYMENT_GATEWAY_CMD, headers, bytes);
        if (!(message instanceof GatewayCommand cmd)) {
            throw new PoisonPillException("Not a GatewayCommand: " + message.getClass().getSimpleName(), null);
        }
        e.setProperty(CMD, cmd);
        String problem = validate(cmd);
        if (problem != null) {
            log.warn("Gateway command {} cannot be sent: {}", cmd.getRequestId(), problem);
            e.setProperty(RESULT, Results.of(cmd, Outcome.ERROR, clock).setMessage(problem).build());
            e.setProperty(REPLAYED, true);               // nothing to store in Redis
        }
    }

    static String validate(GatewayCommand cmd) {
        if (!Set.of("STRIPE", "PAYPAL").contains(cmd.getGateway())) {
            return "unknown gateway " + cmd.getGateway();
        }
        if (!OPERATIONS.contains(cmd.getOperation())) {
            return "unknown operation " + cmd.getOperation();
        }
        if ("AUTHORIZE".equals(cmd.getOperation()) && cmd.getMethodToken() == null) {
            return "AUTHORIZE without a payment method token";
        }
        if (!"AUTHORIZE".equals(cmd.getOperation()) && cmd.getGatewayRef() == null) {
            return cmd.getOperation() + " without the gateway reference of the authorization";
        }
        if ("REFUND".equals(cmd.getOperation()) && "PAYPAL".equals(cmd.getGateway()) && cmd.getCaptureRef() == null) {
            return "PayPal REFUND without a capture id";
        }
        if (cmd.getAmount().signum() <= 0 && !"VOID".equals(cmd.getOperation())) {
            return "amount must be positive";
        }
        return null;
    }

    /** pay:{key}: first attempt -> go on; finished before -> replay the stored answer; running elsewhere -> retry later. */
    void claim(Exchange e) {
        if (e.getProperty(RESULT) != null) {
            return;
        }
        GatewayCommand cmd = cmd(e);
        switch (idempotency.claim(cmd.getIdempotencyKey(), cmd.getRequestId())) {
            case IdempotencyStore.Claim.Claimed c -> {
            }
            case IdempotencyStore.Claim.Done d -> {
                log.info("Idempotency key {} already answered ({}), not calling {} again", cmd.getIdempotencyKey(),
                        d.result().getOutcome(), cmd.getGateway());
                meters.counter("oms.gateway.replays", "gateway", cmd.getGateway(), "operation", cmd.getOperation()).increment();
                e.setProperty(RESULT, Results.replay(d.result(), cmd, clock));
                e.setProperty(REPLAYED, true);
            }
            case IdempotencyStore.Claim.InProgress p -> {
                log.info("Idempotency key {} is being processed by request {}, answering PENDING_RETRY",
                        cmd.getIdempotencyKey(), p.requestId());
                e.setProperty(RESULT, Results.pendingRetry(cmd, "another attempt with this key is in progress", clock));
                e.setProperty(REPLAYED, true);
            }
        }
    }

    void complete(Exchange e) {
        GatewayCommand cmd = cmd(e);
        GatewayResult result = e.getProperty(RESULT, GatewayResult.class);
        idempotency.complete(cmd.getIdempotencyKey(), result);
        meters.counter("oms.gateway.calls", "gateway", cmd.getGateway(), "operation", cmd.getOperation(),
                "outcome", result.getOutcome()).increment();
        log.info("{} {} {} for order {} (key {}): {}{}", cmd.getGateway(), cmd.getOperation(), cmd.getAmount(),
                cmd.getOrderId(), cmd.getIdempotencyKey(), result.getOutcome(),
                result.getMessage() == null ? "" : " - " + result.getMessage());
    }

    /**
     * The HTTP call failed, timed out, or the circuit is open: nothing is known, so try again later with the same key.
     * Camel runs the breaker body on a copy of the exchange and does not hand the failure to the fallback, so the
     * body writes into a {@link CallTrace} (the copy shares the same object) and the fallback reads it.
     */
    void fallback(Exchange e) {
        CallTrace trace = trace(e);
        String why;
        if (trace.failure != null) {
            why = trace.failure;
        } else if (trace.started) {
            why = "TimeoutException: no answer within " + props.callTimeout();
        } else {
            why = "CallNotPermitted: circuit breaker of " + cmd(e).getGateway() + " is open";
        }
        e.setException(null);
        e.setProperty(RESULT, Results.pendingRetry(cmd(e), why, clock));
    }

    /** What happened inside the breaker, for the fallback. */
    static final class CallTrace {
        volatile boolean started;
        volatile String failure;

        void run(Runnable step) {
            try {
                step.run();
            } catch (RuntimeException ex) {
                failure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                throw ex;
            }
        }
    }

    static CallTrace trace(Exchange e) {
        return e.getProperty(TRACE, CallTrace.class);
    }

    static void recordAndRethrow(Exchange e) throws Exception {
        Exception ex = e.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        trace(e).failure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        throw ex;
    }

    /** Only the gateway's headers go out (Camel would map every exchange header to an HTTP header). */
    static void prepare(Exchange e, HttpCall call) {
        e.getMessage().removeHeaders("*");
        call.headers().forEach(e.getMessage()::setHeader);
        e.getMessage().setHeader(Exchange.HTTP_METHOD, call.method());
        e.getMessage().setHeader(Exchange.HTTP_URI, call.url());
        e.getMessage().setBody(call.body());
    }

    void toResultMessage(Exchange e) {
        GatewayCommand cmd = cmd(e);
        GatewayResult result = e.getProperty(RESULT, GatewayResult.class);
        e.getMessage().removeHeaders("*");
        e.getMessage().setHeader(KafkaConstants.KEY, cmd.getOrderId());
        e.getMessage().setHeader(EventHeaders.EVENT_ID, result.getEventId());
        e.getMessage().setHeader(EventHeaders.EVENT_TYPE, "payment.gateway-result");
        String correlation = e.getProperty(CORRELATION, String.class);
        if (correlation != null) {
            e.getMessage().setHeader(EventHeaders.CORRELATION_ID, correlation);
        }
        e.getMessage().setBody(avro.serialize(Topics.PAYMENT_GATEWAY_RESULT, result));
    }

    void toDeadLetter(Exchange e) {
        Throwable cause = e.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        log.warn("Undecodable record on {} sent to {}: {}", Topics.PAYMENT_GATEWAY_CMD, props.dlqTopic(),
                cause == null ? "?" : cause.getMessage());
        byte[] original = e.getProperty(ORIGINAL, byte[].class);
        String key = e.getMessage().getHeader(KafkaConstants.KEY, String.class);
        if (e.getProperty(COMMIT) == null) {
            e.setProperty(COMMIT, e.getMessage().getHeader(KafkaConstants.MANUAL_COMMIT, KafkaManualCommit.class));
        }
        e.getMessage().removeHeaders("*");
        if (key != null) {
            e.getMessage().setHeader(KafkaConstants.KEY, key);
        }
        e.getMessage().setHeader("dlq-reason", cause == null ? "unknown" : String.valueOf(cause.getMessage()));
        e.getMessage().setHeader("dlq-original-topic", Topics.PAYMENT_GATEWAY_CMD);
        e.getMessage().setBody(original == null ? new byte[0] : original);
    }

    void commit(Exchange e) {
        KafkaManualCommit commit = e.getProperty(COMMIT, KafkaManualCommit.class);
        if (commit != null) {
            commit.commit();
        }
    }

    static GatewayCommand cmd(Exchange e) {
        return e.getProperty(CMD, GatewayCommand.class);
    }

    static int status(Exchange e) {
        Integer code = e.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        return code == null ? 0 : code;
    }

    static String body(Exchange e) {
        byte[] bytes = e.getMessage().getBody(byte[].class);
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}
