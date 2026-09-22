package cl.duoc.pedidos360.bff.controller;

import cl.duoc.pedidos360.bff.dto.UserDtos.CreateUserRequest;
import cl.duoc.pedidos360.bff.dto.UserDtos.CreateUserResponse;
import cl.duoc.pedidos360.bff.service.GraphUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alta de usuarios en Entra ID.
 *
 * Expone dos puertas sobre el mismo servicio:
 *  - /api/users/register: publica, la usa el boton "Crear cuenta" del login.
 *    Solo puede crear los roles listados en pedidos360.graph.self-service-roles
 *    (por defecto Customer), para que nadie se auto-asigne Admin.
 *  - /api/users: protegida, solo Admin, permite crear usuarios con cualquier rol.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final GraphUserService graphUserService;

    public UserController(GraphUserService graphUserService) {
        this.graphUserService = graphUserService;
    }

    /** Registro publico invocado desde la pantalla de login (sin JWT). */
    @PostMapping("/register")
    public ResponseEntity<CreateUserResponse> register(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(graphUserService.createUser(request, true));
    }

    /** Alta administrativa: permite elegir cualquier App Role. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CreateUserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(graphUserService.createUser(request, false));
    }
}
