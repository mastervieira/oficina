package pt.oficina.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Plano de manutenção com a data da última intervenção de uma máquina ligada ao plano e a próxima data
 * (última + periodicidade em meses de calendário). Sem intervenção não há próxima data: conta como vencido.
 */
public record PlanoComPrazo(PlanoManutencao plano, LocalDate ultima, LocalDate proxima) {

    public static PlanoComPrazo de(PlanoManutencao plano, LocalDate ultima) {
        LocalDate proxima = ultima == null ? null : ultima.plusMonths(plano.periodicidadeMeses());
        return new PlanoComPrazo(plano, ultima, proxima);
    }

    public boolean semIntervencao() {
        return ultima == null;
    }

    /** Vencido: nunca teve intervenção, ou a próxima data já passou (a data de hoje ainda não está vencida). */
    public boolean vencido(LocalDate hoje) {
        return proxima == null || proxima.isBefore(hoje);
    }

    /** Dias até à próxima data (negativo = em atraso). Só se {@link #semIntervencao()} for falso. */
    public long diasAte(LocalDate hoje) {
        if (proxima == null) {
            throw new IllegalStateException("Plano sem intervenção não tem próxima data.");
        }
        return ChronoUnit.DAYS.between(hoje, proxima);
    }
}
