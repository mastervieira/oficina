package pt.oficina.ui;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import pt.oficina.db.Database;
import pt.oficina.service.EstadoMaquinaService;
import pt.oficina.service.ImportacaoService;
import pt.oficina.service.InventarioService;
import pt.oficina.service.ManutencaoService;
import pt.oficina.service.StockService;
import pt.oficina.ui.comum.Estilos;
import pt.oficina.ui.configuracoes.ConfiguracoesView;
import pt.oficina.ui.estado.EstadoView;
import pt.oficina.ui.inventario.InventarioView;
import pt.oficina.ui.manutencao.ManutencaoView;
import pt.oficina.ui.stock.StockView;

/** Janela principal: um separador por módulo. */
public final class MainView extends BorderPane {

    public MainView(InventarioService inventario, StockService stock, ManutencaoService manutencao,
            EstadoMaquinaService estados, ImportacaoService importacao, Database database) {
        getStylesheets().add(Estilos.url());
        TabPane modulos = new TabPane();
        modulos.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        InventarioView inventarioView = new InventarioView(inventario, stock, importacao);
        StockView stockView = new StockView(stock, inventario, importacao);
        ManutencaoView manutencaoView = new ManutencaoView(manutencao, stock, inventario, importacao);
        EstadoView estadoView = new EstadoView(estados, inventario, importacao, () -> { });
        ConfiguracoesView configuracoesView = new ConfiguracoesView(inventario, manutencao, database);

        Tab tInventario = new Tab("Inventário", inventarioView);
        Tab tStock = new Tab("Stock", stockView);
        Tab tManutencao = new Tab("Manutenção", manutencaoView);
        Tab tEstado = new Tab("Estado das máquinas", estadoView);
        Tab tConfiguracoes = new Tab("Configurações", configuracoesView);

        // Os dados podem ter mudado noutro módulo (máquinas, artigos, stock, estados): recarregar ao voltar.
        recarregarAoSelecionar(tInventario, inventarioView::atualizar);
        recarregarAoSelecionar(tStock, stockView::atualizar);
        recarregarAoSelecionar(tManutencao, manutencaoView::atualizar);
        recarregarAoSelecionar(tEstado, estadoView::atualizar);
        recarregarAoSelecionar(tConfiguracoes, configuracoesView::atualizar);

        modulos.getTabs().addAll(tInventario, tStock, tManutencao, tEstado, tConfiguracoes);
        setCenter(modulos);
    }

    private static void recarregarAoSelecionar(Tab tab, Runnable atualizar) {
        tab.setOnSelectionChanged(e -> {
            if (tab.isSelected()) {
                atualizar.run();
            }
        });
    }
}
