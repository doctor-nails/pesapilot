package io.rafikipay.gateway;

import io.rafikipay.payment.Payment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.gateway", havingValue = "mock", matchIfMissing = true)
public class MockGateway implements PaymentGateway {

    @Override
    public String initiate(Payment payment) {
        System.out.println("[MOCK] STK push to " + payment.phone() + " for KES " + payment.amount());
        return "MOCK-" + UUID.randomUUID().toString().substring(0, 8);
    }
}