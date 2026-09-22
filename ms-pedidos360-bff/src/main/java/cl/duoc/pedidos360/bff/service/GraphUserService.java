package cl.duoc.pedidos360.bff.service;

import cl.duoc.pedidos360.bff.dto.UserDtos.CreateUserRequest;
import cl.duoc.pedidos360.bff.dto.UserDtos.CreateUserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Crea usuarios directamente en Entra ID (Azure AD) a traves de Microsoft Graph.
 *
 * Usa el flujo "client credentials" (app-only): el BFF se autentica con el
 * clientId/clientSecret de una App Registration que tiene concedidos los
 * permisos de aplicacion User.ReadWrite.All y AppRoleAssignment.ReadWrite.All.
 * De este modo el secreto nunca llega al navegador y el alta queda centralizada
 * y auditable en el backend.
 */
@Service
public class GraphUserService {

    private static final Logger log = LoggerFactory.getLogger(GraphUserService.class);

    /** App Roles validos definidos en la App Registration de la API. */
    private static final Set<String> ALLOWED_ROLES = Set.of("Admin", "Operator", "Customer");

    private final WebClient graphWebClient;
    private final WebClient azureTokenWebClient;

    @Value("${pedidos360.graph.tenant-id}")
    private String tenantId;

    @Value("${pedidos360.graph.client-id}")
    private String clientId;

    @Value("${pedidos360.graph.client-secret}")
    private String clientSecret;

    /** Dominio verificado del tenant, ej. "contoso.onmicrosoft.com". */
    @Value("${pedidos360.graph.user-domain}")
    private String userDomain;

    /** appId (client id) de la App Registration de la API, donde viven los App Roles. */
    @Value("${pedidos360.graph.api-app-id}")
    private String apiAppId;

    /** Rol asignado cuando el formulario no especifica ninguno. */
    @Value("${pedidos360.graph.default-role:Customer}")
    private String defaultRole;

    /** Roles que el registro publico puede auto-asignarse (evita que alguien se cree un Admin). */
    @Value("${pedidos360.graph.self-service-roles:Customer}")
    private String selfServiceRoles;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    public GraphUserService(@Qualifier("graphWebClient") WebClient graphWebClient,
                            @Qualifier("azureTokenWebClient") WebClient azureTokenWebClient) {
        this.graphWebClient = graphWebClient;
        this.azureTokenWebClient = azureTokenWebClient;
    }

    /**
     * Crea el usuario en el directorio y le asigna su App Role.
     *
     * @param request     datos del formulario
     * @param selfService true cuando la llamada viene del registro publico del login
     *                    (se restringe a los roles de {@code self-service-roles})
     */
    public CreateUserResponse createUser(CreateUserRequest request, boolean selfService) {
        String role = resolveRole(request.role(), selfService);
        String upn = request.mailNickname().toLowerCase() + "@" + userDomain;
        String token = acquireAppToken();

        String userId = createDirectoryUser(token, request, upn);
        log.info("Usuario creado en Entra ID: {} (id={})", upn, userId);

        boolean roleAssigned = assignAppRole(token, userId, role);

        String message = roleAssigned
                ? "Usuario creado en Entra ID con el rol " + role + "."
                : "Usuario creado en Entra ID, pero no se pudo asignar el rol " + role
                    + ". Un administrador debe asignarlo manualmente en Azure.";

        return new CreateUserResponse(userId, upn, request.displayName(), role, roleAssigned, message);
    }

    // ------------------------------------------------------------------ Graph

    /** POST /v1.0/users: da de alta la cuenta en el directorio. */
    private String createDirectoryUser(String token, CreateUserRequest request, String upn) {
        Map<String, Object> body = Map.of(
                "accountEnabled", true,
                "displayName", request.displayName(),
                "mailNickname", request.mailNickname().toLowerCase(),
                "userPrincipalName", upn,
                // forceChangePasswordNextSignIn=false: en este tenant (External ID/CIAM)
                // el paso de "cambia tu clave" del primer login rompe el flujo de MSAL
                // (AADSTS900561, GET a un endpoint que solo acepta POST). La clave que
                // el usuario define en el registro queda como definitiva.
                "passwordProfile", Map.of(
                        "forceChangePasswordNextSignIn", false,
                        "password", request.password()
                )
        );

        try {
            Map<?, ?> created = graphWebClient.post()
                    .uri("/v1.0/users")
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (created == null || created.get("id") == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Microsoft Graph no devolvio el id del usuario creado");
            }

            String userId = created.get("id").toString();

            // Correo de recuperacion: se guarda como otherMails para que el usuario
            // pueda recuperar su cuenta (no bloquea el alta si falla).
            if (StringUtils.hasText(request.recoveryEmail())) {
                patchOtherMails(token, userId, request.recoveryEmail());
            }
            return userId;

        } catch (WebClientResponseException ex) {
            throw translateGraphError(ex, upn);
        }
    }

    /** PATCH /v1.0/users/{id}: agrega el correo alternativo de recuperacion. */
    private void patchOtherMails(String token, String userId, String recoveryEmail) {
        try {
            graphWebClient.patch()
                    .uri("/v1.0/users/{id}", userId)
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("otherMails", List.of(recoveryEmail)))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException ex) {
            log.warn("No se pudo registrar el correo de recuperacion del usuario {}: {}",
                    userId, ex.getResponseBodyAsString());
        }
    }

    /**
     * Asigna el App Role al usuario recien creado sobre el service principal de la API.
     * Requiere el permiso de aplicacion AppRoleAssignment.ReadWrite.All.
     *
     * @return true si la asignacion quedo hecha; false si fallo (el usuario ya quedo creado igual).
     */
    private boolean assignAppRole(String token, String userId, String role) {
        try {
            Map<?, ?> sps = graphWebClient.get()
                    .uri(uri -> uri.path("/v1.0/servicePrincipals")
                            .queryParam("$filter", "appId eq '" + apiAppId + "'")
                            .queryParam("$select", "id,appRoles")
                            .build())
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            Object rawValue = sps == null ? null : sps.get("value");
            List<?> value = rawValue instanceof List<?> list ? list : List.of();
            if (value.isEmpty()) {
                log.warn("No se encontro el service principal de la API (appId={}); no se asigna rol", apiAppId);
                return false;
            }

            Map<?, ?> sp = (Map<?, ?>) value.get(0);
            String servicePrincipalId = String.valueOf(sp.get("id"));
            String appRoleId = findAppRoleId(sp, role);
            if (appRoleId == null) {
                log.warn("El App Role {} no existe en el service principal {}", role, servicePrincipalId);
                return false;
            }

            return assignAppRoleWithRetry(token, userId, role, servicePrincipalId, appRoleId, 3);

        } catch (WebClientResponseException ex) {
            log.error("Fallo la asignacion del rol {} al usuario {}: {}", role, userId, ex.getResponseBodyAsString());
            return false;
        }
    }

    /**
     * El directorio de Entra ID replica de forma eventual: justo despues de
     * POST /v1.0/users, el nuevo objeto puede no estar aun disponible como
     * principalId en todas las particiones, y Graph responde 400
     * "Not a valid reference update" aunque el rol y los IDs esten bien.
     * Reintentamos con backoff corto antes de rendirnos.
     */
    private boolean assignAppRoleWithRetry(String token, String userId, String role,
                                            String servicePrincipalId, String appRoleId, int attemptsLeft) {
        try {
            graphWebClient.post()
                    .uri("/v1.0/servicePrincipals/{spId}/appRoleAssignedTo", servicePrincipalId)
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "principalId", userId,
                            "resourceId", servicePrincipalId,
                            "appRoleId", appRoleId
                    ))
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("App Role {} asignado al usuario {}", role, userId);
            return true;

        } catch (WebClientResponseException ex) {
            boolean pareceReplicacionPendiente = ex.getStatusCode() == HttpStatus.BAD_REQUEST
                    && ex.getResponseBodyAsString().contains("Not a valid reference update");

            if (pareceReplicacionPendiente && attemptsLeft > 1) {
                log.warn("Asignacion de rol para {} aun no disponible (replicacion del directorio); reintentando... ({} intentos restantes)",
                        userId, attemptsLeft - 1);
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return assignAppRoleWithRetry(token, userId, role, servicePrincipalId, appRoleId, attemptsLeft - 1);
            }

            log.error("Fallo la asignacion del rol {} al usuario {}: {}", role, userId, ex.getResponseBodyAsString());
            return false;
        }
    }

    /** Busca dentro de los appRoles del service principal el id del rol pedido. */
    private String findAppRoleId(Map<?, ?> servicePrincipal, String role) {
        Object appRoles = servicePrincipal.get("appRoles");
        if (!(appRoles instanceof List<?> roles)) {
            return null;
        }
        for (Object item : roles) {
            if (item instanceof Map<?, ?> appRole && role.equalsIgnoreCase(String.valueOf(appRole.get("value")))) {
                return String.valueOf(appRole.get("id"));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Token

    /**
     * Obtiene (y cachea) un access token app-only para Graph.
     * Se renueva 60 s antes de expirar para evitar carreras con el vencimiento.
     */
    private synchronized String acquireAppToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return cachedToken;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type", "client_credentials");
        form.add("scope", "https://graph.microsoft.com/.default");

        try {
            Map<?, ?> response = azureTokenWebClient.post()
                    .uri("/{tenantId}/oauth2/v2.0/token", tenantId)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || response.get("access_token") == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Azure AD no devolvio un access token para Microsoft Graph");
            }

            cachedToken = response.get("access_token").toString();
            long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 3600L;
            cachedTokenExpiry = Instant.now().plusSeconds(Math.max(expiresIn - 60, 30));
            return cachedToken;

        } catch (WebClientResponseException ex) {
            log.error("No se pudo obtener el token de Graph: {}", ex.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo autenticar el BFF contra Microsoft Graph. Revise clientId/clientSecret.");
        }
    }

    // ------------------------------------------------------------------ Apoyo

    /** Valida el rol pedido y aplica la restriccion del registro publico. */
    private String resolveRole(String requested, boolean selfService) {
        String role = StringUtils.hasText(requested) ? requested.trim() : defaultRole;

        String normalized = ALLOWED_ROLES.stream()
                .filter(r -> r.equalsIgnoreCase(role))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Rol invalido. Valores permitidos: Admin, Operator, Customer"));

        if (selfService) {
            boolean permitido = Arrays.stream(selfServiceRoles.split(","))
                    .anyMatch(r -> r.trim().equalsIgnoreCase(normalized));
            if (!permitido) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "El registro publico no permite crear usuarios con el rol " + normalized
                                + ". Solicitelo a un administrador.");
            }
        }
        return normalized;
    }

    /** Convierte el error crudo de Graph en un mensaje util para el formulario. */
    private ResponseStatusException translateGraphError(WebClientResponseException ex, String upn) {
        String raw = ex.getResponseBodyAsString();
        log.error("Graph rechazo el alta de {}: {} - {}", upn, ex.getStatusCode(), raw);

        if (raw.contains("userPrincipalName already exists") || raw.contains("already exists")) {
            return new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un usuario con ese alias: " + upn);
        }
        if (raw.contains("PasswordProfile") || raw.contains("password")) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La contrasena no cumple la politica del directorio (mayusculas, minusculas, numeros o simbolos).");
        }
        if (ex.getStatusCode() == HttpStatus.FORBIDDEN) {
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "La aplicacion no tiene permisos en Graph para crear usuarios (User.ReadWrite.All).");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Microsoft Graph rechazo la creacion del usuario.");
    }
}
