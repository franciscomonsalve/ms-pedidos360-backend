package cl.duoc.pedidos360.bff.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTOs del alta de usuarios en Entra ID (Azure AD).
 * El alta la realiza el BFF contra Microsoft Graph usando client credentials,
 * de modo que el secreto de la app nunca viaja al navegador.
 */
public final class UserDtos {

    private UserDtos() {
    }

    /**
     * Datos que envia el formulario "Crear cuenta" del frontend.
     *
     * @param displayName   nombre visible del usuario (ej. "Ana Perez")
     * @param mailNickname  alias sin dominio; forma el userPrincipalName (ej. "ana.perez")
     * @param password      contraseña inicial; el usuario debera cambiarla en el primer login
     * @param role          App Role a asignar: Admin | Operator | Customer (opcional, por defecto Customer)
     */
    public record CreateUserRequest(
            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 120)
            String displayName,

            @NotBlank(message = "El alias de usuario es obligatorio")
            @Pattern(regexp = "^[a-zA-Z0-9._-]{3,60}$",
                     message = "El alias solo admite letras, numeros, punto, guion y guion bajo (3-60 caracteres)")
            String mailNickname,

            @NotBlank(message = "La contraseña es obligatoria")
            @Size(min = 8, max = 128, message = "La contraseña debe tener al menos 8 caracteres")
            String password,

            String role,

            @Email(message = "El correo de recuperacion no es valido")
            String recoveryEmail
    ) {
    }

    /** Respuesta al frontend tras crear el usuario en el directorio. */
    public record CreateUserResponse(
            String id,
            String userPrincipalName,
            String displayName,
            String assignedRole,
            boolean roleAssigned,
            String message
    ) {
    }
}
