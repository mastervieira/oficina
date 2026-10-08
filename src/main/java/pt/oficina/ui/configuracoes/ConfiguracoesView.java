package pt.oficina.ui.configuracoes;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import pt.oficina.db.Database;
import pt.oficina.service.InventarioService;
import pt.oficina.service.ManutencaoService;

/** Módulo Configurações: listas de apoio, planos de manutenção por tipo de máquina e base de dados. */
public final class ConfiguracoesView extends TabPane {

    private final ListasView listas;
    private final PlanosTipoView planos;
    private final BaseDadosView baseDados;

    public ConfiguracoesView(InventarioService inventario, ManutencaoService manutencao, Database db) {
        setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
        listas = new ListasView(inventario);
        planos = new PlanosTipoView(manutencao, inventario);
        baseDados = new BaseDadosView(db);
        getTabs().addAll(new Tab("Listas", listas), new Tab("Planos de manutenção", planos),
                new Tab("Base de dados", baseDados));
    }

    /** Recarrega os separadores (chamado ao voltar a este módulo). */
    public void atualizar() {
        listas.atualizar();
        planos.atualizar();
        baseDados.atualizar();
    }
}
