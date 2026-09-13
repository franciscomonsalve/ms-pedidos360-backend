package cl.duoc.pedidos360.orders.dto;

import cl.duoc.pedidos360.orders.entity.OrderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public class OrderDtos {

    public record CreateItemRequest(
            @NotNull Long productId,
            @NotBlank String productName,
            @NotNull Integer quantity,
            @NotNull BigDecimal unitPrice
    ) {}

    public record CreateOrderRequest(
            @NotBlank String customerId,
            @NotEmpty List<CreateItemRequest> items
    ) {}

    public record ChangeStatusRequest(
            @NotNull OrderStatus status
    ) {}

    public record OrderResponse(
            Long id,
            String customerId,
            OrderStatus status,
            BigDecimal totalAmount,
            String createdAt,
            String updatedAt,
            List<CreateItemRequest> items
    ) {}
}
