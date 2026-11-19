package cl.duoc.pedidos360.kafkaadmin.service;

/**
 * No se pudo hablar con el cluster. Devuelve 502 y no 500: el fallo no es de
 * este servicio sino de una dependencia aguas abajo, y esa distincion importa
 * cuando alguien mira los registros a las tres de la manana.
 */
public class KafkaNoDisponibleException extends RuntimeException {
    public KafkaNoDisponibleException(String mensaje) {
        super(mensaje);
    }
}
