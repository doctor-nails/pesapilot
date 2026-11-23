package io.rafikipay.gateway;

import io.rafikipay.payment.Payment;

public interface PaymentGateway {

    /** Starts the STK push. Returns the provider's own tracking id (may be null). */
    String initiate(Payment payment);
}