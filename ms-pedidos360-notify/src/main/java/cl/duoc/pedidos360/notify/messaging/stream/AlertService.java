package cl.duoc.pedidos360.notify.messaging.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Vigilancia del flujo de eventos de pedidos.
 *
 * POR QUE ESTO ES UN CASO DE USO DE STREAMING Y NO DE COLAS
 * Este servicio ya recibe COMANDOS por RabbitMQ: "envia este correo", "emite
 * este ticket". Un comando tiene UN destinatario y el emisor espera que alguien
 * lo ejecute.
 *
 * Lo de aqui es otra cosa: observar el flujo de HECHOS para detectar patrones.
 * El productor no sabe que alguien vigila, y manana se pueden sumar mas
 * observadores sin tocarlo. Eso es lo que las colas no dan: en RabbitMQ, un
 * mensaje consumido desaparece; en Kafka el evento queda y cada grupo lo lee
 * por su cuenta.
 *
 * Las dos alertas implementadas:
 *   - cancelaciones, que el negocio quiere revisar
 *   - pedidos de monto alto, que requieren validacion manual
 *
 * NO SABE QUE EXISTE KAFKA: recibe un objeto de dominio. Mismo criterio que
 * CommandProcessor con RabbitMQ y que AuditEventService en el otro servicio.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** Umbral de monto alto. Configurable sin recompilar. */
    @Value("${pedidos360.alertas.monto-alto:100000}")
    private BigDecimal montoAlto;

    /**
     * Contadores en memoria por tipo de evento. Sirven para demostrar que el
     * consumidor realmente proceso lo que dice: el total debe coincidir con el
     * numero de mensajes que Kafka UI muestra consumidos por este grupo.
     */
    private final Map<String, AtomicLong> contadores = new ConcurrentHashMap<>();

    public void evaluar(OrderEvent evento) {
        contadores.computeIfAbsent(evento.eventType(), k -> new AtomicLong()).incrementAndGet();

        if ("OrderCancelled".equals(evento.eventType())) {
            log.warn("[alerta] CANCELACION pedido={} cliente={} venia de {} traceId={}",
                    evento.orderId(), evento.customerId(), evento.previousStatus(), evento.traceId());
        }

        if (evento.totalAmount() != null && evento.totalAmount().compareTo(montoAlto) > 0) {
            log.warn("[alerta] MONTO ALTO pedido={} monto={} (umbral {}) estado={} traceId={}",
                    evento.orderId(), evento.totalAmount(), montoAlto,
                    evento.status(), evento.traceId());
        }

        log.info("[stream] procesado {} del pedido {} (total acumulado de este tipo: {})",
                evento.eventType(), evento.orderId(),
                contadores.get(evento.eventType()).get());
    }

    /** Resumen de lo procesado por este grupo consumidor. */
    public Map<String, Long> resumen() {
        Map<String, Long> copia = new ConcurrentHashMap<>();
        contadores.forEach((tipo, total) -> copia.put(tipo, total.get()));
        return copia;
    }
}
