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

    @Value("${pedidos360.graph.base-url:https://graph.microsoft.com}")
    private String graphBaseUrl;

    @Value("${pedidos360.graph.login-url:https://login.microsoftonline.com}")
    private String azureLoginUrl;

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

    /** Microsoft Graph: alta de usuarios y asignacion de App Roles en Entra ID. */
    @Bean
    public WebClient graphWebClient(WebClient.Builder builder) {
        return builder.baseUrl(graphBaseUrl).build();
    }

    /** Endpoint de token de Azure AD: obtiene el access token app-only para Graph. */
    @Bean
    public WebClient azureTokenWebClient(WebClient.Builder builder) {
        return builder.baseUrl(azureLoginUrl).build();
    }
}
