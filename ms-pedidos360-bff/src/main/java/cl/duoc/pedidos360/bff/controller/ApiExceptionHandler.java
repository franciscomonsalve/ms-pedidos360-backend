package cl.duoc.pedidos360.bff.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Traduce las excepciones a un JSON {"error": "..."} que el formulario del
 * frontend puede mostrar tal cual. Sin esto, Spring devuelve el HTML/JSON
 * generico de error y el usuario no sabria por que fallo el alta.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Errores de negocio lanzados por GraphUserService (409, 400, 403, 502...). */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(body(ex.getReason()));
    }

    /** Errores de validacion de @Valid sobre CreateUserRequest. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detalle = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining(". "));
        return ResponseEntity.badRequest().body(body(detalle));
    }

    /**
     * Error devuelto por un microservicio interno al que el BFF hace de proxy:
     * se propaga el status y el cuerpo originales (ej. un 400 de validacion del
     * administrador de RabbitMQ) en vez de convertirlo en un 500 generico.
     */
    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<String> handleDownstream(WebClientResponseException ex) {
        MediaType type = ex.getHeaders().getContentType() != null ? ex.getHeaders().getContentType() : MediaType.APPLICATION_JSON;
        return ResponseEntity.status(ex.getStatusCode()).contentType(type).body(ex.getResponseBodyAsString());
    }

    private Map<String, Object> body(String mensaje) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", mensaje != null ? mensaje : "Error inesperado");
        return payload;
    }
}
