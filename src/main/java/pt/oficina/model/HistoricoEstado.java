package pt.oficina.model;

import java.time.LocalDate;
import pt.oficina.model.enums.EstadoMaquina;

public record HistoricoEstado(
        Long id,
        long maquinaId,
        LocalDate dataEstado,
        EstadoMaquina estado,
        String motivo) {
}
