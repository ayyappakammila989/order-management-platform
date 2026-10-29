package com.retail.oms.gateway.webhook;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** CIDR allow-list for webhook callers (IPv4 and IPv6). In production it holds the gateways' published ranges. */
public class IpAllowList {

    private record Range(byte[] network, int prefix) {
    }

    private final List<Range> ranges;

    public IpAllowList(List<String> cidrs) {
        this.ranges = cidrs.stream().map(IpAllowList::parse).toList();
    }

    public boolean allows(String address) {
        byte[] ip;
        try {
            ip = InetAddress.getByName(address).getAddress();       // a literal: no DNS lookup
        } catch (UnknownHostException e) {
            return false;
        }
        return ranges.stream().anyMatch(r -> matches(r, ip));
    }

    private static boolean matches(Range r, byte[] ip) {
        if (r.network().length != ip.length) {
            return false;
        }
        int bits = ip.length * 8;
        BigInteger mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
                .shiftRight(r.prefix()).xor(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE));
        return new BigInteger(1, ip).and(mask).equals(new BigInteger(1, r.network()).and(mask));
    }

    private static Range parse(String cidr) {
        String[] parts = cidr.trim().split("/");
        try {
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : network.length * 8;
            if (prefix < 0 || prefix > network.length * 8) {
                throw new IllegalArgumentException("Bad prefix in " + cidr);
            }
            return new Range(network, prefix);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Not an IP range: " + cidr, e);
        }
    }
}
