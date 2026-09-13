package cl.duoc.pedidos360.bff.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Enumeration;

/**
 * Punto unico de entrada del backend para el frontend Angular (patron BFF).
 * Una vez que Spring Security valida el JWT (ver SecurityConfig) y autoriza
 * el rol contra el endpoint solicitado, esta clase reenvia la peticion hacia
 * el microservicio interno correspondiente (orders/catalog/audit/report),
 * propagando el header Authorization para trazabilidad.
 */
@RestController
@RequestMapping("/api")
public class GatewayController {

    private final WebClient ordersWebClient;
    private final WebClient catalogWebClient;
    private final WebClient auditWebClient;
    private final WebClient reportWebClient;

    public GatewayController(@Qualifier("ordersWebClient") WebClient ordersWebClient,
                              @Qualifier("catalogWebClient") WebClient catalogWebClient,
                              @Qualifier("auditWebClient") WebClient auditWebClient,
                              @Qualifier("reportWebClient") WebClient reportWebClient) {
        this.ordersWebClient = ordersWebClient;
        this.catalogWebClient = catalogWebClient;
        this.auditWebClient = auditWebClient;
        this.reportWebClient = reportWebClient;
    }

    @RequestMapping(value = "/orders/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public Mono<ResponseEntity<String>> orders(HttpServletRequest request, @RequestBody(required = false) String body) {
        return proxy(ordersWebClient, "/orders", request, body);
    }

    @RequestMapping(value = "/catalog/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public Mono<ResponseEntity<String>> catalog(HttpServletRequest request, @RequestBody(required = false) String body) {
        return proxy(catalogWebClient, "/catalog", request, body);
    }

    @RequestMapping(value = "/audit/**", method = RequestMethod.GET)
    public Mono<ResponseEntity<String>> audit(HttpServletRequest request) {
        return proxy(auditWebClient, "/audit", request, null);
    }

    @RequestMapping(value = "/report/**", method = RequestMethod.GET)
    public Mono<ResponseEntity<String>> report(HttpServletRequest request) {
        return proxy(reportWebClient, "/report", request, null);
    }

    private Mono<ResponseEntity<String>> proxy(WebClient client, String prefix, HttpServletRequest request, String body) {
        String downstreamPath = request.getRequestURI().replaceFirst("^/api" + prefix, "/api" + prefix);
        String query = request.getQueryString();
        String uri = downstreamPath + (query != null ? "?" + query : "");

        WebClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(request.getMethod())).uri(uri);
        spec.headers(headers -> {
            Enumeration<String> names = request.getHeaderNames() != null ? request.getHeaderNames() : Collections.emptyEnumeration();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                // Content-Length se recalcula por WebClient; el resto (incluye Authorization) se propaga
                if (!"content-length".equalsIgnoreCase(name) && !"host".equalsIgnoreCase(name)) {
                    headers.add(name, request.getHeader(name));
                }
            }
        });

        WebClient.ResponseSpec responseSpec = (body != null)
                ? spec.bodyValue(body).retrieve()
                : spec.retrieve();

        return responseSpec.toEntity(String.class);
    }
}
