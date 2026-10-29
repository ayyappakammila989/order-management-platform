package com.retail.oms.common.web.logging;

import java.util.regex.Pattern;

/** Masks e-mail addresses and phone-like digit runs (architecture section 14.3). */
public final class LogMasking {

    public static final String EMAIL_REGEX = "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}";
    /**
     * 10+ digits, optionally separated by space . - ( ) and a leading +. Must stand alone: digits inside an
     * identifier (order number OMS-20261029-000042, UUIDs, ids like abc1234567890) are kept, because support
     * searches logs by them.
     */
    public static final String PHONE_REGEX = "(?<![\\w-])\\+?\\(?\\d[\\d\\s().-]{8,}\\d(?![\\w-])";

    private static final Pattern EMAIL = Pattern.compile(EMAIL_REGEX);
    private static final Pattern PHONE = Pattern.compile(PHONE_REGEX);

    private LogMasking() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = EMAIL.matcher(text).replaceAll("***@***");
        return PHONE.matcher(masked).replaceAll("***");
    }
}
