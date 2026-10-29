package com.retail.oms.gateway.webhook;

import com.retail.oms.common.web.OmsException;
import org.springframework.http.HttpStatus;

/** Bad or missing signature, or a body that is not a gateway event: 400, the gateway will not retry it forever. */
public class WebhookRejectedException extends OmsException {

    public WebhookRejectedException(String message) {
        super("WHK-400", HttpStatus.BAD_REQUEST, message);
    }
}
