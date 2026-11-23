package io.rafikipay.gateway;

import io.rafikipay.payment.Payment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.gateway", havingValue = "payhero")
public class PayHeroGateway implements PaymentGateway {

    private final RestClient client;
    private final int channelId;
    private final String callbackUrl;

    public PayHeroGateway(@Value("${payhero.base-url}") String baseUrl,
                          @Value("${payhero.auth}") String auth,
                          @Value("${payhero.channel-id}") int channelId,
                          @Value("${payhero.callback-url}") String callbackUrl) {
        this.channelId = channelId;
        this.callbackUrl = callbackUrl;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, auth)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String initiate(Payment payment) {
        Map<String, Object> body = Map.of(
                "amount", payment.amount(),
                "phone_number", toLocalFormat(payment.phone()),
                "channel_id", channelId,
                "provider", "m-pesa",
                "external_reference", payment.reference(),
                "customer_name", payment.name(),
                "callback_url", callbackUrl);

        Map<String, Object> response = client.post()
                .uri("/api/v2/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        System.out.println("PayHero STK response: " + response);

        if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
            throw new IllegalStateException("PayHero rejected the request: " + response);
        }
        Object checkoutId = response.get("CheckoutRequestID");
        return checkoutId == null ? null : String.valueOf(checkoutId);
    }

    private static String toLocalFormat(String phone) {
        String p = phone.replace("+", "").trim();
        return p.startsWith("254") ? "0" + p.substring(3) : p;
    }
}