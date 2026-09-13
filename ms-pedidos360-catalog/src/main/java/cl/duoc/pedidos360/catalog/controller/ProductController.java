package cl.duoc.pedidos360.catalog.controller;

import cl.duoc.pedidos360.catalog.dto.ProductDtos.CreateProductRequest;
import cl.duoc.pedidos360.catalog.dto.ProductDtos.UpdateProductRequest;
import cl.duoc.pedidos360.catalog.entity.Product;
import cl.duoc.pedidos360.catalog.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/catalog/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<Product> create(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    @GetMapping
    public List<Product> findAll() {
        return productService.findAll();
    }

    @GetMapping("/{id}")
    public Product findById(@PathVariable Long id) {
        return productService.findById(id);
    }

    @PutMapping("/{id}")
    public Product update(@PathVariable Long id, @Valid @RequestBody UpdateProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/stock/decrease")
    public Product decreaseStock(@PathVariable Long id, @RequestParam int quantity) {
        return productService.decreaseStock(id, quantity);
    }
}
