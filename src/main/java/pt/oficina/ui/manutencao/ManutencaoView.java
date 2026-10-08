package pt.oficina.ui.manutencao;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import pt.oficina.service.ImportacaoService;
import pt.oficina.service.InventarioService;
import pt.oficina.service.ManutencaoService;
import pt.oficina.service.StockService;

/** Módulo 3: Itinerário, Planos (consulta; definem-se em Configurações, por tipo de máquina) e Intervenções. */
public final class ManutencaoView extends TabPane {

    private final ItinerarioView itinerario;
    private final PlanosView planos;
    private final IntervencoesView intervencoes;

    public ManutencaoView(ManutencaoService svc, StockService stock, InventarioService inventario,
            ImportacaoService importacao) {
        setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
        // Registar uma intervenção muda os três ecrãs.
        Runnable todos = this::atualizar;
        itinerario = new ItinerarioView(svc, stock, inventario, todos);
        planos = new PlanosView(svc, inventario);
        intervencoes = new IntervencoesView(svc, stock, inventario, importacao, todos);

        Tab tItinerario = new Tab("Itinerário", itinerario);
        Tab tPlanos = new Tab("Planos", planos);
        Tab tIntervencoes = new Tab("Intervenções", intervencoes);
        getTabs().addAll(tItinerario, tPlanos, tIntervencoes);
    }

    /** Recarrega os três separadores (também chamado ao voltar a este módulo). */
    public void atualizar() {
        itinerario.atualizar();
        planos.atualizar();
        intervencoes.atualizar();
    }
}
