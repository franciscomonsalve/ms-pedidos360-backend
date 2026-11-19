package cl.duoc.pedidos360.kafkaadmin.service;

/** La peticion no es valida: devuelve 400. */
public class PeticionKafkaInvalidaException extends RuntimeException {
    public PeticionKafkaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
