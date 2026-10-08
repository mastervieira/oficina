package pt.oficina.model;

import java.time.LocalDate;
import pt.oficina.model.enums.EstadoMaquina;

public record Maquina(
        Long id,
        String codigo,
        String descricao,
        String nSerie,
        long categoriaId,
        Long localizacaoId,
        Long fornecedorId,
        LocalDate dataAquisicao,
        EstadoMaquina estado) {

    /** Texto mostrado nas listas. */
    @Override
    public String toString() {
        return codigo + " — " + descricao;
    }
}
