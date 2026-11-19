package cl.duoc.pedidos360.notify.messaging.stream;

/**
 * El evento se deserializo bien pero le faltan campos obligatorios.
 *
 * Marcada como NO REINTENTABLE: un payload invalido no se arregla esperando.
 * Misma distincion que {@code TransientProcessingException} hace del lado de
 * RabbitMQ en este mismo servicio, entre lo que merece reintento y lo que no.
 */
public class EventoNoProcesableException extends RuntimeException {

    public EventoNoProcesableException(String mensaje) {
        super(mensaje);
    }
}
