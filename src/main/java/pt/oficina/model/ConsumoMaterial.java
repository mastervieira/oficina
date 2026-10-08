package pt.oficina.model;

/** Material consumido numa intervenção; regista-se apenas como SAIDA em movimento_stock. */
public record ConsumoMaterial(long artigoId, double quantidade) {
}
