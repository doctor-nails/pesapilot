package io.rafikipay;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI rafikiPayApi() {
        return new OpenAPI().info(new Info()
                .title("RafikiPay API")
                .description("Agentic M-Pesa payments: chat, STK push, callbacks")
                .version("1.0"));
    }
}