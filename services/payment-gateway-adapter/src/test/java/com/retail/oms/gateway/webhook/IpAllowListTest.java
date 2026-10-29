package com.retail.oms.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class IpAllowListTest {

    final IpAllowList list = new IpAllowList(List.of("127.0.0.1/32", "::1/128", "172.16.0.0/12", "3.18.12.63"));

    @Test
    void matchesRangesAndSingleAddresses() {
        assertThat(list.allows("127.0.0.1")).isTrue();
        assertThat(list.allows("0:0:0:0:0:0:0:1")).as("how Tomcat reports ::1").isTrue();
        assertThat(list.allows("172.16.0.1")).isTrue();
        assertThat(list.allows("172.31.255.254")).isTrue();
        assertThat(list.allows("3.18.12.63")).isTrue();
    }

    @Test
    void rejectsOthers() {
        assertThat(list.allows("172.32.0.1")).isFalse();
        assertThat(list.allows("127.0.0.2")).isFalse();
        assertThat(list.allows("3.18.12.64")).isFalse();
        assertThat(list.allows("::2")).isFalse();
        assertThat(list.allows("not-an-ip.invalid")).isFalse();
    }

    @Test
    void badConfigFailsAtStartup() {
        assertThatThrownBy(() -> new IpAllowList(List.of("10.0.0.0/33"))).isInstanceOf(IllegalArgumentException.class);
    }
}
