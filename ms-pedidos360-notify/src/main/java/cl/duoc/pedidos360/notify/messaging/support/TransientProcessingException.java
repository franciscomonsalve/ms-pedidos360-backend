package cl.duoc.pedidos360.notify.messaging.support;

/**
 * Error recuperable al procesar un comando (ej. un proveedor externo caido
 * momentaneamente). El {@link CommandProcessor} lo reintenta UNA vez
 * (requeue) y, si vuelve a fallar, lo envia a la DLQ.
 */
public class TransientProcessingException extends RuntimeException {

    public TransientProcessingException(String message) {
        super(message);
    }

    public TransientProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
