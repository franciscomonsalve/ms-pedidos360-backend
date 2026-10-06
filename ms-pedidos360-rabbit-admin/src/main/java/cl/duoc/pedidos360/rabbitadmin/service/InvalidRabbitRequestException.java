package cl.duoc.pedidos360.rabbitadmin.service;

/** Peticion semanticamente invalida (nombre reservado, parametros inconsistentes...): responde 400. */
public class InvalidRabbitRequestException extends RuntimeException {

    public InvalidRabbitRequestException(String message) {
        super(message);
    }
}
