package com.retail.oms.common.web.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback conversion word {@code %maskedMsg}: the log message with e-mail and phone numbers masked. */
public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogMasking.mask(super.convert(event));
    }
}
