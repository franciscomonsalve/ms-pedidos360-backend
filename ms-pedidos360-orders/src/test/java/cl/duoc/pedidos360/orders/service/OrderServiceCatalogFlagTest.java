package cl.duoc.pedidos360.orders.service;

import cl.duoc.pedidos360.orders.entity.Order;
import cl.duoc.pedidos360.orders.entity.OrderItem;
import cl.duoc.pedidos360.orders.entity.OrderStatus;
import cl.duoc.pedidos360.orders.messaging.CommandPublisher;
import cl.duoc.pedidos360.orders.messaging.OrderEventPublisher;
import cl.duoc.pedidos360.orders.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cubre el flag {@code pedidos360.catalog.enabled} y el mapeo de comandos RabbitMQ por transicion.
 *
 * <p>Contexto: en el EC2 el catalogo no esta desplegado, y ademas orders lo llamaria sin token.
 * Con la reserva de stock activa, TODA transicion a ACEPTADO moria con 409 y el pedido quedaba
 * atascado en CREADO. El flag permite omitir la llamada de forma explicita.
 */
class OrderServiceCatalogFlagTest {

    private OrderRepository repo;
    private CommandPublisher commandPublisher;
    private OrderEventPublisher eventPublisher;
    private RestTemplate restTemplate;
    private OrderService service;

    @BeforeEach
    void setUp() {
        repo = mock(OrderRepository.class);
        commandPublisher = mock(CommandPublisher.class);
        eventPublisher = mock(OrderEventPublisher.class);
        restTemplate = mock(RestTemplate.class);
        service = new OrderService(repo, commandPublisher, eventPublisher, restTemplate);
        ReflectionTestUtils.setField(service, "catalogBaseUrl", "http://catalog:8082");
    }

    private Order pedidoEn(OrderStatus estado) {
        Order o = Order.builder()
                .id(1L)
                .customerId("cliente-123")
                .status(estado)
                .totalAmount(new BigDecimal("10000"))
                .items(List.of(OrderItem.builder().productId(7L).quantity(2).build()))
                .build();
        when(repo.findById(1L)).thenReturn(Optional.of(o));
        when(repo.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        return o;
    }

    private void catalogo(boolean habilitado) {
        ReflectionTestUtils.setField(service, "catalogEnabled", habilitado);
    }

    @Test
    @DisplayName("Con el catalogo apagado, ACEPTADO funciona y no se llama al catalogo")
    void catalogoApagadoNoBloquea() {
        pedidoEn(OrderStatus.CREADO);
        catalogo(false);

        Order resultado = service.changeStatus(1L, OrderStatus.ACEPTADO);

        assertEquals(OrderStatus.ACEPTADO, resultado.getStatus());
        verify(restTemplate, never()).put(anyString(), any());
    }

    @Test
    @DisplayName("Con el catalogo encendido pero caido, ACEPTADO falla con 409 (comportamiento original)")
    void catalogoEncendidoYCaidoDa409() {
        pedidoEn(OrderStatus.CREADO);
        catalogo(true);
        doThrow(new ResourceAccessException("conexion rechazada"))
                .when(restTemplate).put(anyString(), any());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.changeStatus(1L, OrderStatus.ACEPTADO));

        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("ACEPTADO publica email (topic) y ticket de cocina, pero no boleta")
    void aceptadoPublicaEmailYCocina() {
        Order o = pedidoEn(OrderStatus.CREADO);
        catalogo(false);

        service.changeStatus(1L, OrderStatus.ACEPTADO);

        verify(commandPublisher).publishEmailNotification(o);
        verify(commandPublisher).publishKitchenTicket(o);
        verify(commandPublisher, never()).publishInvoiceGeneration(any());
    }

    @Test
    @DisplayName("ENTREGADO publica email y boleta, pero no ticket de cocina")
    void entregadoPublicaEmailYBoleta() {
        Order o = pedidoEn(OrderStatus.DESPACHADO);
        catalogo(false);

        service.changeStatus(1L, OrderStatus.ENTREGADO);

        verify(commandPublisher).publishEmailNotification(o);
        verify(commandPublisher).publishInvoiceGeneration(o);
        verify(commandPublisher, never()).publishKitchenTicket(any());
    }

    @Test
    @DisplayName("Una transicion intermedia publica solo el email")
    void transicionIntermediaPublicaSoloEmail() {
        Order o = pedidoEn(OrderStatus.ACEPTADO);
        catalogo(false);

        service.changeStatus(1L, OrderStatus.EN_PREPARACION);

        verify(commandPublisher).publishEmailNotification(o);
        verify(commandPublisher, never()).publishKitchenTicket(any());
        verify(commandPublisher, never()).publishInvoiceGeneration(any());
    }

    @Test
    @DisplayName("Una transicion invalida da 409 y no publica nada")
    void transicionInvalidaNoPublica() {
        pedidoEn(OrderStatus.CREADO);
        catalogo(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.changeStatus(1L, OrderStatus.ENTREGADO));

        assertEquals(409, ex.getStatusCode().value());
        verify(commandPublisher, never()).publishEmailNotification(any());
    }
}
