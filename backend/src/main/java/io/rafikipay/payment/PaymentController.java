package io.rafikipay.payment;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    public record PayRequest(String phone, int amount, String name) {}

    @PostMapping("/pay")
    public Payment pay(@RequestBody PayRequest request) {
        return service.requestPayment(request.phone(), request.amount(), request.name());
    }

    @GetMapping("/pay/{reference}")
    public Payment status(@PathVariable String reference) {
        return service.get(reference);
    }

    @GetMapping("/payments")
    public List<Payment> all() {
        return service.all();
    }

    @PostMapping("/simulate/pin/{reference}")
    public Payment simulatePin(@PathVariable String reference) {
        return service.simulatePin(reference);
    }
}