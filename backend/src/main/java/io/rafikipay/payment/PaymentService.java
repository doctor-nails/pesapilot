package io.rafikipay.payment;

import io.rafikipay.gateway.PaymentGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PaymentService {

    private static final Pattern PHONE = Pattern.compile("^(\\+?254|0)[17]\\d{8}$");

    private final PaymentStore store;
    private final PaymentGateway gateway;
    private final String gatewayMode;

    public PaymentService(PaymentStore store,
                          PaymentGateway gateway,
                          @Value("${app.gateway:mock}") String gatewayMode) {
        this.store = store;
        this.gateway = gateway;
        this.gatewayMode = gatewayMode;
    }

    public Payment requestPayment(String phone, int amount, String name) {
        if (phone == null || !PHONE.matcher(phone.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Kenyan phone number");
        }
        if (amount < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be at least 1");
        }

        String reference = "RP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Payment payment = store.save(new Payment(
                reference,
                phone.trim(),
                amount,
                (name == null || name.isBlank()) ? "Customer" : name.trim(),
                PaymentStatus.PENDING,
                null,
                Instant.now().toString()));

        try {
            String providerRef = gateway.initiate(payment);
            Payment latest = store.findByReference(reference).orElse(payment);
            return store.save(latest.withProviderRef(providerRef));
        } catch (RuntimeException e) {
            System.err.println("Gateway failed: " + e.getMessage());
            return store.save(payment.withStatus(PaymentStatus.FAILED));
        }
    }

    public Payment get(String reference) {
        return store.findByReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
    }

    public List<Payment> all() {
        return store.all();
    }

    /** The single place a payment's outcome is recorded: real callbacks AND the simulator use it. */
    public Payment applyResult(String reference, String providerRef, boolean success) {
        Payment payment = store.findByReference(reference)
                .or(() -> store.findByProviderRef(providerRef))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

        if (payment.status() != PaymentStatus.PENDING) {
            return payment;
        }
        return store.save(payment.withStatus(success ? PaymentStatus.SUCCESS : PaymentStatus.FAILED));
    }

    /** Demo only: pretends the customer entered their PIN. */
    public Payment simulatePin(String reference) {
        if (!"mock".equalsIgnoreCase(gatewayMode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Simulator only works in mock mode");
        }
        return applyResult(reference, null, true);
    }
}