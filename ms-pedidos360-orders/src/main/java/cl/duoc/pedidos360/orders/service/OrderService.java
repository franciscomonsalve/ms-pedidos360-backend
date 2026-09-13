package cl.duoc.pedidos360.orders.service;

import cl.duoc.pedidos360.orders.dto.OrderDtos.CreateOrderRequest;
import cl.duoc.pedidos360.orders.entity.Order;
import cl.duoc.pedidos360.orders.entity.OrderItem;
import cl.duoc.pedidos360.orders.entity.OrderStatus;
import cl.duoc.pedidos360.orders.messaging.CommandPublisher;
import cl.duoc.pedidos360.orders.messaging.OrderEventPublisher;
import cl.duoc.pedidos360.orders.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CommandPublisher commandPublisher;
    private final OrderEventPublisher orderEventPublisher;
    private final RestTemplate restTemplate;

    @Value("${pedidos360.catalog.base-url}")
    private String catalogBaseUrl;

    public OrderService(OrderRepository orderRepository,
                         CommandPublisher commandPublisher,
                         OrderEventPublisher orderEventPublisher,
                         RestTemplate restTemplate) {
        this.orderRepository = orderRepository;
        this.commandPublisher = commandPublisher;
        this.orderEventPublisher = orderEventPublisher;
        this.restTemplate = restTemplate;
    }

    @Transactional
    public Order create(CreateOrderRequest request) {
        Order order = Order.builder()
                .customerId(request.customerId())
                .status(OrderStatus.CREADO)
                .createdAt(Instant.now())
                .totalAmount(BigDecimal.ZERO)
                .build();

        BigDecimal total = BigDecimal.ZERO;
        for (var itemReq : request.items()) {
            OrderItem item = OrderItem.builder()
                    .productId(itemReq.productId())
                    .productName(itemReq.productName())
                    .quantity(itemReq.quantity())
                    .unitPrice(itemReq.unitPrice())
                    .build();
            order.addItem(item);
            total = total.add(itemReq.unitPrice().multiply(BigDecimal.valueOf(itemReq.quantity())));
        }
        order.setTotalAmount(total);

        Order saved = orderRepository.save(order);
        orderEventPublisher.publish(saved, null);
        return saved;
    }

    public List<Order> findAll() {
        return orderRepository.findAll();
    }

    public Order findById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado: " + id));
    }

    /**
     * Cambia el estado del pedido validando la maquina de estados del dominio.
     * Regla clave: no se puede pasar a DESPACHADO sin haber pasado por ACEPTADO.
     * Al aceptar, decrementa stock en el microservicio de catalogo.
     */
    @Transactional
    public Order changeStatus(Long id, OrderStatus target) {
        Order order = findById(id);
        OrderStatus current = order.getStatus();

        if (!current.canTransitionTo(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Transicion invalida: no se puede pasar de " + current + " a " + target);
        }

        if (target == OrderStatus.ACEPTADO) {
            decreaseStockOnCatalog(order);
        }

        order.setStatus(target);
        order.setUpdatedAt(Instant.now());
        if (target == OrderStatus.ENTREGADO) {
            order.setDeliveredAt(Instant.now());
        }

        Order saved = orderRepository.save(order);

        // Publica evento de dominio (Kafka) y comandos asincronos (RabbitMQ)
        orderEventPublisher.publish(saved, current);
        commandPublisher.publishEmailNotification(saved);
        if (target == OrderStatus.ACEPTADO) {
            commandPublisher.publishKitchenTicket(saved);
        }
        if (target == OrderStatus.ENTREGADO) {
            commandPublisher.publishInvoiceGeneration(saved);
        }

        return saved;
    }

    private void decreaseStockOnCatalog(Order order) {
        try {
            for (OrderItem item : order.getItems()) {
                restTemplate.put(
                        catalogBaseUrl + "/api/catalog/products/" + item.getProductId() + "/stock/decrease?quantity=" + item.getQuantity(),
                        null);
            }
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No fue posible reservar stock en catalogo: " + ex.getMessage());
        }
    }
}
