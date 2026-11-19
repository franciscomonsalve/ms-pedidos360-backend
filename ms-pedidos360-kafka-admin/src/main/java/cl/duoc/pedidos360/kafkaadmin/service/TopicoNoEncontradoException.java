package cl.duoc.pedidos360.kafkaadmin.service;

/** El topico no existe: devuelve 404. */
public class TopicoNoEncontradoException extends RuntimeException {
    public TopicoNoEncontradoException(String nombre) {
        super("no existe el topico: " + nombre);
    }
}
