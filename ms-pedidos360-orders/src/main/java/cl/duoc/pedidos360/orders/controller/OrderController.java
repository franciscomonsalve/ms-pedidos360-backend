package cl.duoc.pedidos360.orders.controller;

import cl.duoc.pedidos360.orders.dto.OrderDtos.ChangeStatusRequest;
import cl.duoc.pedidos360.orders.dto.OrderDtos.CreateOrderRequest;
import cl.duoc.pedidos360.orders.entity.Order;
import cl.duoc.pedidos360.orders.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<Order> create(@Valid @RequestBody CreateOrderRequest request) {
        Order created = orderService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<Order> findAll() {
        return orderService.findAll();
    }

    @GetMapping("/{id}")
    public Order findById(@PathVariable Long id) {
        return orderService.findById(id);
    }

    @PatchMapping("/{id}/status")
    public Order changeStatus(@PathVariable Long id, @Valid @RequestBody ChangeStatusRequest request) {
        return orderService.changeStatus(id, request.status());
    }
}
