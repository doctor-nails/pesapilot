package io.rafikipay.payment;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PaymentStore {

    private final Map<String, Payment> payments = new ConcurrentHashMap<>();

    public PaymentStore() {
        // Pre-seed sample transactions for instant demonstration
        save(new Payment(
                "RP-NJU78901",
                "0722123456",
                1500,
                "Njuguna",
                PaymentStatus.SUCCESS,
                "ws_CO_DEMO_NJUGUNA",
                Instant.now().minusSeconds(3600).toString()
        ));
        save(new Payment(
                "RP-DON12345",
                "0728016048",
                500,
                "Don",
                PaymentStatus.SUCCESS,
                "ws_CO_DEMO_DON",
                Instant.now().minusSeconds(1800).toString()
        ));
    }

    public Payment save(Payment payment) {
        payments.put(payment.reference(), payment);
        return payment;
    }

    public Optional<Payment> findByReference(String reference) {
        if (reference == null) return Optional.empty();
        return Optional.ofNullable(payments.get(reference));
    }

    public Optional<Payment> findByProviderRef(String providerRef) {
        if (providerRef == null) return Optional.empty();
        return payments.values().stream()
                .filter(p -> providerRef.equals(p.providerRef()))
                .findFirst();
    }

    public List<Payment> all() {
        return payments.values().stream()
                .sorted(Comparator.comparing(Payment::createdAt).reversed())
                .toList();
    }
}
