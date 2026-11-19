package cl.duoc.pedidos360.kafkaadmin.controller;

import cl.duoc.pedidos360.kafkaadmin.service.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/** Respuestas de error uniformes: {"error": "...", "details": [...]}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        return ResponseEntity.badRequest().body(Map.of("error", "Validacion fallida", "details", details));
    }

    @ExceptionHandler(PeticionKafkaInvalidaException.class)
    public ResponseEntity<Map<String, Object>> onInvalida(PeticionKafkaInvalidaException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> onIllegible(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Cuerpo de la peticion ilegible o con valores no permitidos"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> onFaltaParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Falta el parametro obligatorio '" + ex.getParameterName() + "'"));
    }

    @ExceptionHandler({TopicoNoEncontradoException.class, GrupoNoEncontradoException.class})
    public ResponseEntity<Map<String, Object>> onNoEncontrado(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(TopicoYaExisteException.class)
    public ResponseEntity<Map<String, Object>> onYaExiste(TopicoYaExisteException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    /**
     * 502 y no 500: el fallo no es de este servicio sino del cluster al que
     * consulta. La distincion ahorra tiempo cuando hay que diagnosticar.
     */
    @ExceptionHandler(KafkaNoDisponibleException.class)
    public ResponseEntity<Map<String, Object>> onKafkaCaido(KafkaNoDisponibleException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "No se pudo comunicar con Kafka: " + ex.getMessage()));
    }
}
