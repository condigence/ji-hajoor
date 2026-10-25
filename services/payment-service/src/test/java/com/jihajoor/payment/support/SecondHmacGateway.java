package com.jihajoor.payment.support;

import com.jihajoor.payment.gateway.AbstractHmacGatewayAdapter;

/**
 * A second HMAC-signed gateway for tests that need two registered gateways with different
 * secrets (cross-gateway callback checks). Production registers Razorpay only, plus the simulator
 * when explicitly enabled.
 */
public final class SecondHmacGateway extends AbstractHmacGatewayAdapter {

    public static final String GATEWAY_ID = "second-gateway";

    public SecondHmacGateway(String signingSecret) {
        super(signingSecret);
    }

    @Override
    public String gatewayId() {
        return GATEWAY_ID;
    }
}
