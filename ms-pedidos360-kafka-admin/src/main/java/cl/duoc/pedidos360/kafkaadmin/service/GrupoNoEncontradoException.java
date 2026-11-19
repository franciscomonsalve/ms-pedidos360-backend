package cl.duoc.pedidos360.kafkaadmin.service;

/** El consumer group no existe: devuelve 404. */
public class GrupoNoEncontradoException extends RuntimeException {
    public GrupoNoEncontradoException(String grupo) {
        super("no existe el grupo de consumidores: " + grupo);
    }
}
