package cl.duoc.pedidos360.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${pedidos360.downstream.orders-url}")
    private String ordersUrl;

    @Value("${pedidos360.downstream.catalog-url}")
    private String catalogUrl;

    @Value("${pedidos360.downstream.audit-url}")
    private String auditUrl;

    @Value("${pedidos360.downstream.report-url}")
    private String reportUrl;

    @Bean
    public WebClient ordersWebClient(WebClient.Builder builder) {
        return builder.baseUrl(ordersUrl).build();
    }

    @Bean
    public WebClient catalogWebClient(WebClient.Builder builder) {
        return builder.baseUrl(catalogUrl).build();
    }

    @Bean
    public WebClient auditWebClient(WebClient.Builder builder) {
        return builder.baseUrl(auditUrl).build();
    }

    @Bean
    public WebClient reportWebClient(WebClient.Builder builder) {
        return builder.baseUrl(reportUrl).build();
    }
}
