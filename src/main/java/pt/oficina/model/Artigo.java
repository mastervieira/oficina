package pt.oficina.model;

/** O stock atual não faz parte do modelo: calcula-se a partir de movimento_stock. */
public record Artigo(
        Long id,
        String codigo,
        String descricao,
        long categoriaId,
        Long localizacaoId,
        Long fornecedorId,
        String unidade,
        double stockMinimo) {
}
