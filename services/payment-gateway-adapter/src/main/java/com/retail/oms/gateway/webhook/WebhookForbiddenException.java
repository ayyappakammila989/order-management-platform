package com.retail.oms.gateway.webhook;

import com.retail.oms.common.web.OmsException;
import org.springframework.http.HttpStatus;

public class WebhookForbiddenException extends OmsException {

    public WebhookForbiddenException(String address) {
        super("WHK-403", HttpStatus.FORBIDDEN, "Webhook from an address that is not allowed: " + address);
    }
}
