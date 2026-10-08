package pt.oficina.model;

import java.time.LocalDate;
import pt.oficina.model.enums.TipoIntervencao;

/** {@code planoId} e {@code custo} são opcionais (null). */
public record Intervencao(
        Long id,
        long maquinaId,
        Long planoId,
        LocalDate dataInterv,
        TipoIntervencao tipo,
        String descricao,
        Double custo) {
}
