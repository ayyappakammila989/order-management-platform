package com.retail.oms.pricing.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.retail.oms.common.web.OmsException;
import com.retail.oms.pricing.application.QuoteService;
import com.retail.oms.pricing.domain.Quote;
import com.retail.oms.pricing.infrastructure.persistence.PromotionRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL (Flyway migration + seed data), real Redis and WireMock standing in for tax-service.
 * Proves: seeded prices/promotions produce the expected quote, Redis keys and TTLs from architecture 8, tax comes
 * from the cache on the second call, and what happens when the tax provider fails.
 */
@Testcontainers
@SpringBootTest(properties = {
        // 10+ failures are needed to open the breaker; this test class makes only a few
        "oms.pricing.tax-breaker.minimum-calls=100"
})
class PricingQuoteIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @RegisterExtension
    static final WireMockExtension TAX = WireMockExtension.newInstance().options(
            com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void taxUrl(DynamicPropertyRegistry registry) {
        registry.add("oms.tax-service.url", TAX::baseUrl);
    }

    @Autowired QuoteService quoteService;
    @Autowired StringRedisTemplate redis;
    @Autowired PromotionRepository promotions;

    @BeforeEach
    void clean() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        TAX.resetAll();
        TAX.stubFor(post(urlEqualTo("/internal/v1/tax/calculate")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"zip\":\"10001\",\"state\":\"NY\",\"rate\":0.08875,\"source\":\"test\"}")));
    }

    @Test
    void flywaySeedsTheSampleCatalogAndPromotions() {
        assertThat(promotions.count()).isEqualTo(5);
        assertThat(promotions.findLive(java.time.Instant.parse("2026-10-29T00:00:00Z")))
                .extracting(p -> p.getName())
                .contains("SKU-2 15% off", "Spring 10% off orders from 50")
                .doesNotContain("Expired coupon");
    }

    @Test
    void quoteUsesSeededPricesAutomaticPromotionAndTaxFromTheProvider() {
        // SKU-2: 2 x 49.50 = 99.00, automatic 15% off = 14.85, tax 8.875% of 84.15 = 7.47
        Quote quote = quoteService.quote("USD", "10001", null, List.of(new QuoteService.Line("SKU-2", 2)));

        assertThat(quote.subtotal()).isEqualByComparingTo("99.00");
        assertThat(quote.discountTotal()).isEqualByComparingTo("14.85");
        assertThat(quote.taxTotal()).isEqualByComparingTo("7.47");
        assertThat(quote.shippingTotal()).isEqualByComparingTo("0.00");   // 84.15 >= 75
        assertThat(quote.grandTotal()).isEqualByComparingTo("91.62");
        TAX.verify(1, postRequestedFor(urlEqualTo("/internal/v1/tax/calculate"))
                .withRequestBody(equalToJson("{\"zip\":\"10001\",\"taxCode\":\"GENERAL\"}")));
    }

    @Test
    void couponIsAppliedAndAnExpiredOneIsRefused() {
        Quote quote = quoteService.quote("USD", "10001", "spring10", List.of(new QuoteService.Line("SKU-4", 1)));
        assertThat(quote.discountTotal()).isEqualByComparingTo("12.90");     // 10% of 129.00, case-insensitive code

        assertThatThrownBy(() -> quoteService.quote("USD", "10001", "OLD20", List.of(new QuoteService.Line("SKU-1", 1))))
                .isInstanceOf(OmsException.class).hasMessageContaining("OLD20");
    }

    @Test
    void secondQuoteComesFromRedisAndTheCachesHaveTheDesignedTtls() {
        quoteService.quote("USD", "10001", null, List.of(new QuoteService.Line("SKU-1", 1)));
        quoteService.quote("USD", "10001", null, List.of(new QuoteService.Line("SKU-1", 1)));

        TAX.verify(1, postRequestedFor(urlEqualTo("/internal/v1/tax/calculate")));   // second time: cache
        assertThat(redis.hasKey("price:SKU-1")).isTrue();
        assertThat(redis.hasKey("promo:active")).isTrue();
        assertThat(redis.hasKey("tax:10001:GENERAL")).isTrue();
        assertThat(redis.getExpire("price:SKU-1")).isBetween(590L, 600L);          // 10 min
        assertThat(redis.getExpire("promo:active")).isBetween(290L, 300L);         // 5 min
        assertThat(redis.getExpire("tax:10001:GENERAL")).isBetween(86_390L, 86_400L);   // 24 h
    }

    @Test
    void cachedTaxRateKeepsQuotesWorkingWhenTheProviderGoesDown() {
        quoteService.quote("USD", "10001", null, List.of(new QuoteService.Line("SKU-1", 1)));
        TAX.stubFor(post(urlEqualTo("/internal/v1/tax/calculate")).willReturn(aResponse().withStatus(503)));

        Quote quote = quoteService.quote("USD", "10001", null, List.of(new QuoteService.Line("SKU-1", 1)));

        assertThat(quote.taxTotal()).isEqualByComparingTo("1.77");           // 19.99 x 0.08875 = 1.774
        TAX.verify(1, postRequestedFor(urlEqualTo("/internal/v1/tax/calculate")));
    }

    @Test
    void providerDownAndNothingCachedIs503() {
        TAX.stubFor(post(urlEqualTo("/internal/v1/tax/calculate")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> quoteService.quote("USD", "99999", null, List.of(new QuoteService.Line("SKU-1", 1))))
                .isInstanceOfSatisfying(OmsException.class, e -> assertThat(e.getErrorCode()).isEqualTo("PRC-503"));
        assertThat(redis.hasKey("tax:99999:GENERAL")).isFalse();             // a failure is never cached
    }

    @Test
    void slowProviderIsCutOffByTheReadTimeout() {
        TAX.stubFor(post(urlEqualTo("/internal/v1/tax/calculate")).willReturn(aResponse().withFixedDelay(4000)
                .withHeader("Content-Type", "application/json").withBody("{\"rate\":0.05}")));

        long start = System.nanoTime();
        assertThatThrownBy(() -> quoteService.quote("USD", "73301", null, List.of(new QuoteService.Line("SKU-1", 1))))
                .isInstanceOf(OmsException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3500));   // read timeout 2 s
    }

    @Test
    void redisOutageFallsBackToTheDatabaseInsteadOfFailing() {
        // Container paused, not stopped, so the mapped port stays the same for the other tests
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            Quote quote = quoteService.quote("USD", "10001", null, List.of(new QuoteService.Line("SKU-3", 1)));
            assertThat(quote.subtotal()).isEqualByComparingTo("5.00");
            assertThat(quote.discountTotal()).isEqualByComparingTo("1.00");   // SKU-3 promotion read from PostgreSQL
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }
}
