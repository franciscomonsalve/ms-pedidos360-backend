package cl.duoc.pedidos360.kafkaadmin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Valida el JWT (issuer y audience de application.yml) como defensa en
 * profundidad: administrar la plataforma de streaming es una operacion
 * sensible -se pueden borrar topicos enteros- y solo la puede hacer un usuario
 * con rol Admin, aunque alguien llegara a este servicio saltandose el BFF o el
 * API Gateway.
 *
 * Ademas, este servicio NO publica puerto hacia fuera en el compose: solo es
 * alcanzable dentro de la red de Docker, a traves del BFF. Mismo criterio que
 * ms-pedidos360-rabbit-admin.
 */
@Configuration
public class SecurityConfig {

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

    /**
     * Cadena SOLO para desarrollo local, activa con el perfil {@code local}.
     *
     * PARA QUE EXISTE: para poder probar y demostrar la administracion de Kafka
     * sin levantar el stack completo ni depender de Entra ID. Obtener un JWT
     * valido exige frontend, BFF y conexion al proveedor de identidad; para
     * verificar que un topico se crea con 3 particiones eso sobra.
     *
     * NO SE ACTIVA NUNCA EN DESPLIEGUE: hay que pedirlo explicitamente con
     * SPRING_PROFILES_ACTIVE=local. Sin ese perfil manda la cadena de abajo,
     * que exige rol Admin.
     */
    @Bean
    @org.springframework.context.annotation.Profile("local")
    public SecurityFilterChain filterChainLocal(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @org.springframework.context.annotation.Profile("!local")
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/kafka/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            );
        return http.build();
    }
}
