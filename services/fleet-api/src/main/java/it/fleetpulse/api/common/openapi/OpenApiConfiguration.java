package it.fleetpulse.api.common.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    /**
     * Configura i metadati generali della documentazione FleetPulse.
     */
    @Bean
    public OpenAPI fleetPulseOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FleetPulse API")
                        .description(
                                "API REST operative per la gestione dei veicoli di FleetPulse."
                        )
                        .version("v1"));
    }
}
