package com.jihajoor.auth.sms;

import java.time.Duration;

/**
 * Hexagonal port abstracting the outbound SMS gateway.
 *
 * <p>The OTP registration flow depends only on this interface, never on a concrete
 * vendor SDK. Adding a new provider (Twilio, MSG91, SNS, ...) requires only a new
 * adapter implementing this port — no change to the OTP business logic. This mirrors
 * the {@code PaymentGatewayPort} / {@code SmsPort} abstraction pattern established in
 * the platform design.
 */
public interface SmsGatewayPort {

    /**
     * Sends an SMS message to the supplied mobile number.
     *
     * @param mobileNumber E.164 mobile number (e.g. {@code +919876543210})
     * @param message      the message body to deliver
     * @throws SmsDeliveryException if the number is undeliverable/invalid or the gateway
     *                              fails to accept the message (Requirement 1.16)
     */
    void send(String mobileNumber, String message) throws SmsDeliveryException;

    /**
     * Sends a one-time sign-in code. Gateways that deliver OTPs through a pre-approved template
     * (MSG91, and any provider bound by India's DLT rules) override this to pass the code itself;
     * the default renders {@link #otpMessage} and sends it as plain text.
     *
     * @param mobileNumber E.164 mobile number
     * @param code         the numeric one-time code
     * @param validity     how long the code stays valid
     * @throws SmsDeliveryException if the gateway does not accept the message
     */
    default void sendOtp(String mobileNumber, String code, Duration validity) throws SmsDeliveryException {
        send(mobileNumber, otpMessage(code, validity));
    }

    /**
     * The OTP text. It must stay identical to the DLT-registered template, with the code in
     * place of the template's variable.
     */
    static String otpMessage(String code, Duration validity) {
        return "Your Ji-Hajoor verification code is " + code
                + ". It expires in " + validity.toMinutes() + " minutes.";
    }
}
