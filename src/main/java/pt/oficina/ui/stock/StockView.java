package pt.oficina.ui.stock;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import pt.oficina.service.ImportacaoService;
import pt.oficina.service.InventarioService;
import pt.oficina.service.StockService;

/** Módulo 2: Stock atual e Movimentos. */
public final class StockView extends TabPane {

    private final StockAtualView atual;
    private final MovimentosView movimentos;

    public StockView(StockService stock, InventarioService inventario, ImportacaoService importacao) {
        setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
        movimentos = new MovimentosView(stock, importacao);
        atual = new StockAtualView(stock, inventario, movimentos::atualizar);

        Tab tAtual = new Tab("Stock atual", atual);
        Tab tMov = new Tab("Movimentos", movimentos);
        tAtual.setOnSelectionChanged(e -> {
            if (tAtual.isSelected()) {
                atual.atualizar();
            }
        });
        tMov.setOnSelectionChanged(e -> {
            if (tMov.isSelected()) {
                movimentos.atualizar();
            }
        });
        getTabs().addAll(tAtual, tMov);
    }

    /** Recarrega os dois separadores (chamado ao voltar a este módulo). */
    public void atualizar() {
        atual.atualizar();
        movimentos.atualizar();
    }
}
