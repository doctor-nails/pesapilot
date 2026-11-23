package io.rafikipay.gateway;

import io.rafikipay.payment.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/payhero")
public class CallbackController {

    private final PaymentService service;

    public CallbackController(PaymentService service) {
        this.service = service;
    }

    @PostMapping("/callback")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> callback(@RequestBody Map<String, Object> raw) {
        System.out.println("PayHero callback received: " + raw);

        Map<String, Object> r = raw.get("response") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        String reference = r.get("ExternalReference") == null ? null : String.valueOf(r.get("ExternalReference"));
        String checkoutId = r.get("CheckoutRequestID") == null ? null : String.valueOf(r.get("CheckoutRequestID"));
        boolean success = "0".equals(String.valueOf(r.get("ResultCode")))
                || "success".equalsIgnoreCase(String.valueOf(r.get("Status")));

        try {
            service.applyResult(reference, checkoutId, success);
        } catch (ResponseStatusException e) {
            System.err.println("Callback for unknown payment: " + reference);
        }
        return ResponseEntity.ok().build();
    }
}