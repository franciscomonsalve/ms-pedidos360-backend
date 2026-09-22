package cl.duoc.pedidos360.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Configura la validacion del JWT emitido por Azure AD (IDaaS) en el BFF.
 *
 * El CORS ya NO se maneja aqui: el navegador solo habla con AWS API Gateway
 * (nunca directo con el BFF), asi que CORS se configura del lado del API
 * Manager. Si el BFF tambien agregara sus propios headers Access-Control-*,
 * quedarian duplicados sobre los del Gateway y el navegador rechazaria la
 * respuesta.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.audiences}")
    private String expectedAudience;

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder jwtDecoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> withIssuer =
                JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> audienceValidator =
                new AudienceValidator(expectedAudience);

        jwtDecoder.setJwtValidator(new DelegatingAudienceIssuerValidator(withIssuer, audienceValidator));
        return jwtDecoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Object rolesClaim = jwt.getClaim("roles");
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            if (rolesClaim instanceof List<?> rolesList) {
                for (Object role : rolesList) {
                    if (role != null) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toString().toUpperCase()));
                    }
                }
            }
            return authorities;
        });
        return converter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // Preflight CORS: el navegador nunca manda Authorization en un OPTIONS.
                // API Gateway ya enruta el OPTIONS real hasta aca (ruta propia sin
                // Authorizer JWT), asi que el BFF tambien debe dejarlo pasar sin JWT.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Registro autoservicio desde la pantalla de login: no hay JWT todavia.
                // El rol que puede crearse esta limitado por pedidos360.graph.self-service-roles.
                .requestMatchers(HttpMethod.POST, "/api/users/register").permitAll()
                // Alta administrativa con cualquier rol (ademas de @PreAuthorize en el controller)
                .requestMatchers("/api/users/**").hasRole("ADMIN")
                .requestMatchers("/api/catalog/**").hasAnyRole("ADMIN", "OPERATOR")
                .requestMatchers("/api/report/**").hasRole("ADMIN")
                .requestMatchers("/api/audit/**").hasRole("ADMIN")
                .requestMatchers("/api/orders/**").hasAnyRole("ADMIN", "OPERATOR", "CUSTOMER")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer((OAuth2ResourceServerConfigurer<HttpSecurity> oauth2) -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            )
            .exceptionHandling(ex -> ex.accessDeniedHandler((request, response, accessDeniedException) ->
                response.sendError(HttpStatus.FORBIDDEN.value(), "No tiene permisos suficientes para este recurso")));

        return http.build();
    }

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
