package com.fluxupi.common.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI fluxUpiOpenApi() {
        return new OpenAPI().info(new Info()
                .title("FluxUPI API")
                .version("0.1.0")
                .description("""
                        Simulated UPI-based digital credit line engine.

                        Everything is mocked: no real bank, NPCI or UPI rail is integrated,
                        no real money moves, no KYC. Lenders, VPAs and transactions are
                        fixtures within this service.

                        Core guarantees demonstrated by the test suite: 100%% double-entry
                        ledger reconciliation, and idempotent, race-safe transaction
                        processing under concurrent spends.
                        """)
                .license(new License().name("Portfolio / learning project")));
    }
}
