package cl.duoc.pedidos360.audit.messaging;

/**
 * El evento llego, se deserializo bien, pero no se puede procesar: le faltan
 * campos obligatorios o sus valores no tienen sentido.
 *
 * Esta excepcion esta registrada como NO REINTENTABLE en KafkaConsumerConfig.
 * La distincion importa: un fallo transitorio (la base no responde un instante)
 * si merece reintentos, porque la siguiente vez puede funcionar. Un payload
 * invalido no se arregla esperando, asi que va directo a la DLT y el consumidor
 * sigue con el siguiente mensaje sin perder dos segundos.
 *
 * Es la misma distincion que CommandProcessor aplica en los consumidores de
 * RabbitMQ entre "error transitorio" y "error no recuperable".
 */
public class EventoNoProcesableException extends RuntimeException {

    public EventoNoProcesableException(String mensaje) {
        super(mensaje);
    }
}
