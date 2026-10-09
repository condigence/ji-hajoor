package com.jihajoor.payment.gateway;

/** Result of initiating a charge with a {@link PaymentGatewayPort}. */
public record GatewayChargeResult(String gatewayReference, boolean accepted) {
}
