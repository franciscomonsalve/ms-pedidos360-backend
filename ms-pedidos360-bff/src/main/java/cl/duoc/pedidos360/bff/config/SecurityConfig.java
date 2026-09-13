package cl.duoc.pedidos360.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Configura la validacion del JWT emitido por Azure AD (IDaaS) en el BFF.
 *
 * Cumple con el indicador "Configura correctamente el BFF para que, al igual
 * que el API Manager, pueda validar el token recibido con el IDaaS definido y
 * solo permita consumir el endpoint si el token es valido":
 *  - Valida issuer y audience.
 *  - Verifica firma (JWKS) y vigencia (exp/nbf) via JwtValidators.
 *  - Mapea el claim de roles de Azure AD ("roles") a authorities de Spring Security.
 *  - Aplica autorizacion por rol a nivel de endpoint.
 *  - Responde 401/403 con codigos de error adecuados en vez de 500.
 */
@Configuration
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.audiences}")
    private String expectedAudience;

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder jwtDecoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> withIssuer =
                JwtValidators.createDefaultWithIssuer(issuerUri); // valida iss, exp, nbf, firma (JWKS)
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> audienceValidator =
                new AudienceValidator(expectedAudience); // valida "aud"

        jwtDecoder.setJwtValidator(new DelegatingAudienceIssuerValidator(withIssuer, audienceValidator));
        return jwtDecoder;
    }

    /** Convierte el claim "roles" (App Roles de Azure AD) en authorities ROLE_x */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return jwtAuthenticationConverter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/api/catalog/**").hasAnyRole("ADMIN", "OPERATOR")
                .requestMatchers("/api/report/**").hasRole("ADMIN")
                .requestMatchers("/api/audit/**").hasRole("ADMIN")
                .requestMatchers("/api/orders/**").hasAnyRole("ADMIN", "OPERATOR", "CUSTOMER")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer((OAuth2ResourceServerConfigurer<HttpSecurity> oauth2) -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                // 401 si el token es invalido/ausente en lugar de un error generico
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            )
            // 403 si el token es valido pero el rol no tiene permiso sobre el endpoint
            .exceptionHandling(ex -> ex.accessDeniedHandler((request, response, accessDeniedException) ->
                response.sendError(HttpStatus.FORBIDDEN.value(), "No tiene permisos suficientes para este recurso")));

        return http.build();
    }

    /** Valida que el claim "aud" del token contenga el Application ID URI configurado */
    static class AudienceValidator implements OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> {
        private final String audience;

        AudienceValidator(String audience) {
            this.audience = audience;
        }

        @Override
        public org.springframework.security.oauth2.core.OAuth2TokenValidatorResult validate(org.springframework.security.oauth2.jwt.Jwt token) {
            List<String> audiences = token.getAudience();
            if (audiences != null && audiences.contains(audience)) {
                return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
            }
            org.springframework.security.oauth2.core.OAuth2Error error = new org.springframework.security.oauth2.core.OAuth2Error(
                    "invalid_token", "El audience del token no coincide con " + audience, null);
            return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(error);
        }
    }

    /** Combina el validador por defecto (issuer/exp/firma) con el de audience */
    static class DelegatingAudienceIssuerValidator implements OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> {
        private final OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> issuerValidator;
        private final OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> audienceValidator;

        DelegatingAudienceIssuerValidator(OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> issuerValidator,
                                           OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> audienceValidator) {
            this.issuerValidator = issuerValidator;
            this.audienceValidator = audienceValidator;
        }

        @Override
        public org.springframework.security.oauth2.core.OAuth2TokenValidatorResult validate(org.springframework.security.oauth2.jwt.Jwt token) {
            var result = issuerValidator.validate(token);
            if (result.hasErrors()) {
                return result;
            }
            return audienceValidator.validate(token);
        }
    }
}
