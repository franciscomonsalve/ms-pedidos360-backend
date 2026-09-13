package cl.duoc.pedidos360.orders.entity;

/**
 * Estados del ciclo de vida de un pedido.
 * Regla clave del caso: no se puede "despachar" sin haber "aceptado" antes.
 */
public enum OrderStatus {
    CREADO,
    ACEPTADO,
    EN_PREPARACION,
    DESPACHADO,
    ENTREGADO,
    CANCELADO;

    /** Valida si la transicion desde este estado hacia el destino esta permitida */
    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case CREADO -> target == ACEPTADO || target == CANCELADO;
            case ACEPTADO -> target == EN_PREPARACION || target == CANCELADO;
            case EN_PREPARACION -> target == DESPACHADO || target == CANCELADO;
            case DESPACHADO -> target == ENTREGADO;
            case ENTREGADO, CANCELADO -> false; // estados finales
        };
    }
}
