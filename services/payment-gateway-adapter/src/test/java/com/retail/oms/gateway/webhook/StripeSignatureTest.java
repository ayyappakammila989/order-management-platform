package com.retail.oms.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StripeSignatureTest {

    static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    static final byte[] BODY = "{\"id\":\"evt_1\",\"type\":\"refund.updated\"}".getBytes(StandardCharsets.UTF_8);

    final StripeSignature signature = new StripeSignature("whsec_test", Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void validSignatureIsAccepted() {
        assertThatCode(() -> signature.verify(signature.header(NOW.getEpochSecond(), BODY), BODY)).doesNotThrowAnyException();
    }

    @Test
    void knownStripeExampleFormatWithSeveralSignatures() {
        // Stripe may send several v1 values (secret rotation) and a v0; any matching v1 is enough
        String good = signature.header(NOW.getEpochSecond(), BODY);
        String header = "t=" + NOW.getEpochSecond() + ",v1=" + "0".repeat(64) + "," + good.substring(good.indexOf("v1=")) + ",v0=abc";
        assertThatCode(() -> signature.verify(header, BODY)).doesNotThrowAnyException();
    }

    @Test
    void tamperedBodyIsRejected() {
        String header = signature.header(NOW.getEpochSecond(), BODY);
        byte[] tampered = "{\"id\":\"evt_1\",\"type\":\"refund.updated\",\"amount\":999999}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> signature.verify(header, tampered)).isInstanceOf(WebhookRejectedException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void otherSecretIsRejected() {
        var other = new StripeSignature("whsec_other", Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> signature.verify(other.header(NOW.getEpochSecond(), BODY), BODY))
                .isInstanceOf(WebhookRejectedException.class);
    }

    @Test
    void timestampToleranceIsFiveMinutesBothWays() {
        long t = NOW.getEpochSecond();
        assertThatCode(() -> signature.verify(signature.header(t - 300, BODY), BODY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> signature.verify(signature.header(t - 301, BODY), BODY))
                .hasMessageContaining("outside the tolerance");
        assertThatThrownBy(() -> signature.verify(signature.header(t + 301, BODY), BODY))
                .hasMessageContaining("outside the tolerance");
    }

    @Test
    void malformedHeadersAreRejected() {
        assertThatThrownBy(() -> signature.verify(null, BODY)).hasMessageContaining("missing");
        assertThatThrownBy(() -> signature.verify("v1=abc", BODY)).hasMessageContaining("needs t= and v1=");
        assertThatThrownBy(() -> signature.verify("t=abc,v1=abc", BODY)).hasMessageContaining("not a number");
    }
}
