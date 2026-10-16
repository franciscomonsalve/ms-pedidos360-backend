package cl.duoc.pedidos360.orders.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fija las reglas de autorizacion POR METODO HTTP de la API de pedidos.
 *
 * <p>Existe por un bug real: la configuracion usaba
 * {@code requestMatchers("PATCH", "/api/orders/*​/status")} sin importar
 * {@link org.springframework.http.HttpMethod}. Esa sobrecarga recibe PATRONES DE RUTA, no verbos,
 * asi que "PATCH" se interpretaba como una ruta: la regla anterior ({@code "/api/orders/**"})
 * capturaba todos los metodos y la de PATCH quedaba como codigo muerto. Resultado: un CUSTOMER
 * podia cambiar el estado de cualquier pedido.
 *
 * <p>El caso que de verdad importa es
 * {@link #customerNoPuedeCambiarEstado()}: si alguien vuelve a escribir el verbo como String,
 * esa prueba falla.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SecurityConfigTest {

    private static final String STATUS_URL = "/api/orders/1/status";
    private static final String BODY = "{\"status\":\"ACEPTADO\"}";

    @Autowired
    private MockMvc mockMvc;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor rol(String rol) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    @Test
    @DisplayName("PATCH /status: un CUSTOMER recibe 403 (regresion del bug de verbos como String)")
    void customerNoPuedeCambiarEstado() throws Exception {
        mockMvc.perform(patch(STATUS_URL).with(rol("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /status: ADMIN y OPERATOR pasan la capa de autorizacion")
    void adminYOperatorPuedenCambiarEstado() throws Exception {
        for (String r : new String[]{"ADMIN", "OPERATOR"}) {
            mockMvc.perform(patch(STATUS_URL).with(rol(r))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    // No se afirma 2xx: el pedido 1 no existe en la base de pruebas. Lo que se
                    // verifica es que NO lo frena la autorizacion.
                    .andExpect(result -> {
                        int s = result.getResponse().getStatus();
                        if (s == 401 || s == 403) {
                            throw new AssertionError("El rol " + r + " deberia pasar la autorizacion, pero dio " + s);
                        }
                    });
        }
    }

    @Test
    @DisplayName("PATCH /status sin token: 401")
    void sinTokenNoPasa() throws Exception {
        mockMvc.perform(patch(STATUS_URL)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/orders: los tres roles pueden listar")
    void losTresRolesPuedenListar() throws Exception {
        for (String r : new String[]{"ADMIN", "OPERATOR", "CUSTOMER"}) {
            mockMvc.perform(get("/api/orders").with(rol(r)))
                    .andExpect(status().isOk());
        }
    }
}
