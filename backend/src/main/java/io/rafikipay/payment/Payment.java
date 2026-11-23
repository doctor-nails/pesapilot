package io.rafikipay.payment;

public record Payment(
        String reference,
        String phone,
        int amount,
        String name,
        PaymentStatus status,
        String providerRef,
        String createdAt) {

    public Payment withStatus(PaymentStatus newStatus) {
        return new Payment(reference, phone, amount, name, newStatus, providerRef, createdAt);
    }

    public Payment withProviderRef(String newProviderRef) {
        return new Payment(reference, phone, amount, name, status, newProviderRef, createdAt);
    }
}