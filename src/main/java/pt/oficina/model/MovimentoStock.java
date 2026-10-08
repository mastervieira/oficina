package pt.oficina.model;

import java.time.LocalDate;
import pt.oficina.model.enums.TipoMovimento;

public record MovimentoStock(
        Long id,
        long artigoId,
        LocalDate dataMov,
        TipoMovimento tipo,
        double quantidade,
        String nota) {
}
