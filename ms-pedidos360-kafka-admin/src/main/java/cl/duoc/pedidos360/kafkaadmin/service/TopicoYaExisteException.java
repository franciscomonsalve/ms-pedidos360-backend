package cl.duoc.pedidos360.kafkaadmin.service;

/** Ya existe un topico con ese nombre: devuelve 409. */
public class TopicoYaExisteException extends RuntimeException {
    public TopicoYaExisteException(String mensaje) {
        super(mensaje);
    }
}
