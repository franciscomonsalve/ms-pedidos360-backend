package cl.duoc.pedidos360.catalog.service;

import cl.duoc.pedidos360.catalog.dto.ProductDtos.CreateProductRequest;
import cl.duoc.pedidos360.catalog.dto.ProductDtos.UpdateProductRequest;
import cl.duoc.pedidos360.catalog.entity.Product;
import cl.duoc.pedidos360.catalog.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Product create(CreateProductRequest request) {
        Product product = Product.builder()
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .stock(request.stock())
                .active(true)
                .build();
        return productRepository.save(product);
    }

    public List<Product> findAll() {
        return productRepository.findAll();
    }

    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado: " + id));
    }

    public Product update(Long id, UpdateProductRequest request) {
        Product product = findById(id);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setActive(request.active());
        return productRepository.save(product);
    }

    public void delete(Long id) {
        productRepository.delete(findById(id));
    }

    /**
     * Decrementa stock al aceptar un pedido (regla clave del caso).
     * Es invocado por ms-pedidos360-orders al transicionar a ACEPTADO.
     */
    @Transactional
    public Product decreaseStock(Long id, int quantity) {
        Product product = findById(id);
        if (product.getStock() < quantity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Stock insuficiente para el producto " + id + " (disponible: " + product.getStock() + ")");
        }
        product.setStock(product.getStock() - quantity);
        return productRepository.save(product);
    }
}
