package cl.duoc.pedidos360.rabbitadmin.service;

import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.BindingRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.ExchangeKind;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.ExchangeRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.MessageEnvelope;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.PublishRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.PublishResponse;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.QueueInfoResponse;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.QueueRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.NAME_MESSAGE;
import static cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.NAME_PATTERN;

/**
 * Toda la logica de administracion del broker vive aqui: el controller solo
 * traduce HTTP y no conoce nada de RabbitMQ. Las altas/bajas usan AMQP
 * (AmqpAdmin); los listados, la API de gestion (RabbitManagementClient).
 *
 * Reglas de validacion que se aplican ademas de Bean Validation de los DTOs:
 *  - nombres con el formato permitido tambien en parametros de ruta/consulta,
 *  - prefijo "amq." reservado por RabbitMQ,
 *  - 404 si la cola/exchange no existe, 409 si ya existe.
 */
@Service
public class RabbitAdminService {

    private static final Logger log = LoggerFactory.getLogger(RabbitAdminService.class);
    private static final Pattern NAME = Pattern.compile(NAME_PATTERN);
    private static final String RESERVED_PREFIX = "amq.";

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final RabbitManagementClient management;

    public RabbitAdminService(AmqpAdmin amqpAdmin, RabbitTemplate rabbitTemplate, RabbitManagementClient management) {
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
        this.management = management;
    }

    // --------------------------------------------------------------- Colas

    public QueueInfoResponse createQueue(QueueRequest request) {
        String name = validName(request.name(), "cola");
        boolean withDlq = Boolean.TRUE.equals(request.withDeadLetter());

        String dlx = request.deadLetterExchange();
        if (withDlq && (dlx == null || dlx.isBlank())) {
            throw new InvalidRabbitRequestException("deadLetterExchange es obligatorio cuando withDeadLetter=true");
        }
        if (!withDlq && dlx != null && !dlx.isBlank()) {
            throw new InvalidRabbitRequestException("deadLetterExchange solo aplica cuando withDeadLetter=true");
        }
        if (queueExists(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cola '" + name + "' ya existe");
        }

        QueueBuilder builder = request.durable() == null || request.durable()
                ? QueueBuilder.durable(name) : QueueBuilder.nonDurable(name);

        if (withDlq) {
            requireExchange(dlx);
            String dlRoutingKey = request.deadLetterRoutingKey() == null || request.deadLetterRoutingKey().isBlank()
                    ? name : request.deadLetterRoutingKey();
            String dlqName = name + ".dlq";
            if (queueExists(dlqName)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "La DLQ '" + dlqName + "' ya existe");
            }
            runAmqp("crear la cola " + name, () -> {
                amqpAdmin.declareQueue(builder
                        .withArgument("x-dead-letter-exchange", dlx)
                        .withArgument("x-dead-letter-routing-key", dlRoutingKey)
                        .build());
                amqpAdmin.declareQueue(QueueBuilder.durable(dlqName).build());
                amqpAdmin.declareBinding(new Binding(dlqName, Binding.DestinationType.QUEUE, dlx, dlRoutingKey, null));
            });
            log.info("Cola creada name={} con DLQ={} (dlx={}, routingKey={})", name, dlqName, dlx, dlRoutingKey);
        } else {
            runAmqp("crear la cola " + name, () -> amqpAdmin.declareQueue(builder.build()));
            log.info("Cola creada name={}", name);
        }
        return queueInfo(name);
    }

    public QueueInfoResponse queueInfo(String rawName) {
        String name = validName(rawName, "cola");
        QueueInformation info = amqpAdmin.getQueueInfo(name);
        if (info == null) {
            throw notFound("La cola '" + name + "' no existe");
        }
        return new QueueInfoResponse(info.getName(), info.getMessageCount(), info.getConsumerCount());
    }

    public void deleteQueue(String rawName, boolean ifUnused, boolean ifEmpty) {
        String name = validName(rawName, "cola");
        if (!queueExists(name)) {
            throw notFound("La cola '" + name + "' no existe");
        }
        try {
            amqpAdmin.deleteQueue(name, ifUnused, ifEmpty);
        } catch (AmqpException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se pudo eliminar la cola '" + name + "' (en uso o con mensajes segun los filtros pedidos)");
        }
        log.info("Cola eliminada name={} ifUnused={} ifEmpty={}", name, ifUnused, ifEmpty);
    }

    public List<Map<String, Object>> listQueues() {
        return management.listQueues();
    }

    // ----------------------------------------------------------- Exchanges

    public ExchangeRequest createExchange(ExchangeRequest request) {
        String name = validName(request.name(), "exchange");
        if (exchangeExists(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El exchange '" + name + "' ya existe");
        }
        boolean durable = request.durable() == null || request.durable();
        runAmqp("crear el exchange " + name, () -> amqpAdmin.declareExchange(buildExchange(name, request.type(), durable)));
        log.info("Exchange creado name={} type={} durable={}", name, request.type(), durable);
        return new ExchangeRequest(name, request.type(), durable);
    }

    public void deleteExchange(String rawName) {
        String name = validName(rawName, "exchange");
        if (!exchangeExists(name)) {
            throw notFound("El exchange '" + name + "' no existe");
        }
        runAmqp("eliminar el exchange " + name, () -> amqpAdmin.deleteExchange(name));
        log.info("Exchange eliminado name={}", name);
    }

    public List<Map<String, Object>> listExchanges() {
        return management.listExchanges();
    }

    // ------------------------------------------------------------ Bindings

    public BindingRequest createBinding(BindingRequest request) {
        Binding binding = toBinding(request.queue(), request.exchange(), request.routingKey());
        runAmqp("crear el binding", () -> amqpAdmin.declareBinding(binding));
        log.info("Binding creado queue={} exchange={} routingKey='{}'",
                binding.getDestination(), binding.getExchange(), binding.getRoutingKey());
        return new BindingRequest(binding.getDestination(), binding.getExchange(), binding.getRoutingKey());
    }

    public void deleteBinding(String queue, String exchange, String routingKey) {
        Binding binding = toBinding(queue, exchange, routingKey);
        runAmqp("eliminar el binding", () -> amqpAdmin.removeBinding(binding));
        log.info("Binding eliminado queue={} exchange={} routingKey='{}'",
                binding.getDestination(), binding.getExchange(), binding.getRoutingKey());
    }

    public List<Map<String, Object>> listBindings() {
        return management.listBindings();
    }

    // ----------------------------------------------------- Mensaje de prueba

    public PublishResponse publish(PublishRequest request) {
        String exchange = validName(request.exchange(), "exchange");
        requireExchange(exchange);
        String routingKey = request.routingKey() == null ? "" : request.routingKey();
        String type = request.type() == null || request.type().isBlank() ? "admin.test" : request.type();

        MessageEnvelope envelope = new MessageEnvelope(type, UUID.randomUUID().toString(), Instant.now(),
                UUID.randomUUID().toString(), "admin", request.payload());
        runAmqp("publicar el mensaje", () -> rabbitTemplate.convertAndSend(exchange, routingKey, envelope));
        log.info("Mensaje de prueba publicado exchange={} routingKey='{}' eventId={}", exchange, routingKey, envelope.eventId());
        return new PublishResponse(envelope.eventId(), exchange, routingKey);
    }

    // ------------------------------------------------------------- Helpers

    private Binding toBinding(String queue, String exchange, String routingKey) {
        String queueName = validName(queue, "cola");
        String exchangeName = validName(exchange, "exchange");
        if (!queueExists(queueName)) {
            throw notFound("La cola '" + queueName + "' no existe");
        }
        requireExchange(exchangeName);
        return new Binding(queueName, Binding.DestinationType.QUEUE, exchangeName,
                routingKey == null ? "" : routingKey, null);
    }

    private static Exchange buildExchange(String name, ExchangeKind kind, boolean durable) {
        ExchangeBuilder builder = switch (kind) {
            case DIRECT -> ExchangeBuilder.directExchange(name);
            case TOPIC -> ExchangeBuilder.topicExchange(name);
            case FANOUT -> ExchangeBuilder.fanoutExchange(name);
            case HEADERS -> ExchangeBuilder.headersExchange(name);
        };
        return builder.durable(durable).build();
    }

    /** Valida formato y reserva de nombres, tambien para parametros de ruta/consulta. */
    private String validName(String name, String what) {
        if (name == null || name.isBlank()) {
            throw new InvalidRabbitRequestException("El nombre de la " + what + " no puede estar vacio");
        }
        if (!NAME.matcher(name).matches()) {
            throw new InvalidRabbitRequestException("Nombre de " + what + " invalido: " + NAME_MESSAGE);
        }
        if (name.startsWith(RESERVED_PREFIX)) {
            throw new InvalidRabbitRequestException(
                    "El prefijo '" + RESERVED_PREFIX + "' esta reservado por RabbitMQ (" + what + " '" + name + "')");
        }
        return name;
    }

    private boolean queueExists(String name) {
        return amqpAdmin.getQueueProperties(name) != null;
    }

    /** AMQP no expone un "existe exchange": se hace un declare pasivo, que falla si no existe. */
    private boolean exchangeExists(String name) {
        try {
            return Boolean.TRUE.equals(rabbitTemplate.execute(channel -> {
                channel.exchangeDeclarePassive(name);
                return Boolean.TRUE;
            }));
        } catch (AmqpException ex) {
            return false;
        }
    }

    private void requireExchange(String name) {
        if (!exchangeExists(name)) {
            throw notFound("El exchange '" + name + "' no existe");
        }
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    /** Ejecuta una operacion AMQP traduciendo cualquier fallo del broker a un 502 legible. */
    private void runAmqp(String action, Runnable operation) {
        try {
            operation.run();
        } catch (AmqpException ex) {
            log.error("Fallo al {}: {}", action, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "RabbitMQ rechazo la operacion (" + action + "): " + ex.getMessage());
        }
    }
}
