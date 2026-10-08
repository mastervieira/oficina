package pt.oficina.model;

/**
 * Plano de manutenção de um TIPO de máquina (uma categoria de máquinas): vale para todas as máquinas desse tipo.
 * Quando a próxima intervenção é devida calcula-se por máquina (ver {@link PlanoComPrazo}).
 */
public record PlanoManutencao(Long id, long categoriaId, String tarefa, int periodicidadeMeses) {

    /** Texto mostrado nas listas. */
    @Override
    public String toString() {
        return tarefa + " (a cada " + periodicidadeMeses + (periodicidadeMeses == 1 ? " mês)" : " meses)");
    }
}
