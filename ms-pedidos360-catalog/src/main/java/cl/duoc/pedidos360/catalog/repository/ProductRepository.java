package cl.duoc.pedidos360.catalog.repository;

import cl.duoc.pedidos360.catalog.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
