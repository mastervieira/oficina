package pt.oficina.ui.inventario;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import pt.oficina.service.ImportacaoService;
import pt.oficina.service.InventarioService;
import pt.oficina.service.StockService;

/** Módulo 1: Máquinas e Artigos. As listas de apoio (categorias, etc.) estão em Configurações. */
public final class InventarioView extends TabPane {

    private final MaquinasView maquinas;
    private final ArtigosView artigos;

    public InventarioView(InventarioService svc, StockService stock, ImportacaoService importacao) {
        setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);

        maquinas = new MaquinasView(svc, importacao);
        artigos = new ArtigosView(svc, stock, importacao);

        Tab tMaquinas = new Tab("Máquinas", maquinas);
        Tab tArtigos = new Tab("Artigos", artigos);
        getTabs().addAll(tMaquinas, tArtigos);
    }

    /** Recarrega os dois separadores (chamado ao voltar a este módulo). */
    public void atualizar() {
        maquinas.atualizar();
        artigos.atualizar();
    }
}
