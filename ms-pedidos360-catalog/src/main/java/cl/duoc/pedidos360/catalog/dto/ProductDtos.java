package cl.duoc.pedidos360.catalog.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class ProductDtos {

    public record CreateProductRequest(
            @NotBlank String name,
            String description,
            @NotNull @DecimalMin("0.0") BigDecimal price,
            @NotNull @Min(0) Integer stock
    ) {}

    public record UpdateProductRequest(
            @NotBlank String name,
            String description,
            @NotNull @DecimalMin("0.0") BigDecimal price,
            boolean active
    ) {}
}
