package pt.oficina.ui.stock;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableRow;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import pt.oficina.excel.ExcelExportacao;
import pt.oficina.model.StockArtigo;
import pt.oficina.model.enums.TipoMovimento;
import pt.oficina.service.Catalogos;
import pt.oficina.service.InventarioService;
import pt.oficina.service.StockService;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.Estilos;
import pt.oficina.ui.comum.ExcelUi;
import pt.oficina.ui.comum.Formatos;
import pt.oficina.ui.comum.TabelaPesquisavel;

/** Stock atual por artigo, com alerta de stock abaixo do mínimo e registo de entradas e saídas. */
public final class StockAtualView extends VBox {

    private final StockService stock;
    private final InventarioService inventario;
    private final Runnable aoAlterar;
    private Catalogos cat = Catalogos.vazio();
    private final TabelaPesquisavel<StockArtigo> tabela;
    private final Label resumo = new Label();

    /** @param aoAlterar chamado depois de gravar um movimento (para atualizar o separador de movimentos) */
    public StockAtualView(StockService stock, InventarioService inventario, Runnable aoAlterar) {
        this.stock = stock;
        this.inventario = inventario;
        this.aoAlterar = aoAlterar;
        tabela = new TabelaPesquisavel<>(s -> String.join(" ",
                s.artigo().codigo(), s.artigo().descricao(), cat.categoria(s.artigo().categoriaId()),
                cat.localizacao(s.artigo().localizacaoId()), cat.fornecedor(s.artigo().fornecedorId())));

        tabela.addColuna("Código", s -> s.artigo().codigo(), 100);
        tabela.addColuna("Descrição", s -> s.artigo().descricao(), 280);
        tabela.addColuna("Categoria", s -> cat.categoria(s.artigo().categoriaId()), 140);
        tabela.addColuna("Localização", s -> cat.localizacao(s.artigo().localizacaoId()), 230);
        tabela.addColuna("Stock atual", s -> Formatos.numero(s.stock()), 90);
        tabela.addColuna("Mínimo", s -> Formatos.numero(s.artigo().stockMinimo()), 80);
        tabela.addColuna("Unid.", s -> s.artigo().unidade(), 60);
        tabela.addColuna("Alerta", s -> s.abaixoDoMinimo() ? "Abaixo do mínimo" : "", 130);

        tabela.tabela().setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(StockArtigo item, boolean empty) {
                super.updateItem(item, empty);
                Estilos.classe(this, "linha-alerta", !empty && item != null && item.abaixoDoMinimo());
            }
        });

        CheckBox soAlertas = new CheckBox("Só abaixo do mínimo");
        soAlertas.selectedProperty().addListener((o, a, sel) ->
                tabela.setFiltroExtra(s -> !sel || s.abaixoDoMinimo()));

        Button entrada = new Button("Entrada");
        entrada.setOnAction(e -> registar(TipoMovimento.ENTRADA));
        Button saida = new Button("Saída");
        saida.setOnAction(e -> registar(TipoMovimento.SAIDA));
        entrada.disableProperty().bind(tabela.tabela().getSelectionModel().selectedItemProperty().isNull());
        saida.disableProperty().bind(tabela.tabela().getSelectionModel().selectedItemProperty().isNull());
        tabela.setAoDuploClique(s -> registar(TipoMovimento.ENTRADA));
        Button exportar = new Button("Exportar Excel…");
        exportar.setOnAction(e -> {
            // cópias: a exportação corre fora do fio da interface e não deve ler a tabela do ecrã
            var itens = tabela.itensVisiveis();
            var c = cat;
            ExcelUi.exportar(getScene().getWindow(), "artigos", "Exportados", "stock-atual",
                    itens.size(), tabela.totalItens(), destino -> ExcelExportacao.stockAtual(destino, itens, c));
        });
        tabela.barra().getChildren().addAll(soAlertas, entrada, saida, exportar, resumo);

        VBox.setVgrow(tabela, Priority.ALWAYS);
        getChildren().add(tabela);
        atualizar();
    }

    public void atualizar() {
        Dialogos.tentar(() -> {
            cat = inventario.catalogos();
            var todos = stock.stockAtual();
            tabela.setItens(todos);
            tabela.tabela().refresh();
            long alertas = todos.stream().filter(StockArtigo::abaixoDoMinimo).count();
            resumo.setText(alertas == 0 ? "" : alertas + (alertas == 1 ? " artigo abaixo do mínimo" : " artigos abaixo do mínimo"));
            resumo.setStyle("-fx-text-fill: #b00020; -fx-font-weight: bold;");
        });
    }

    private void registar(TipoMovimento tipo) {
        StockArtigo sel = tabela.selecionado();
        if (sel != null && MovimentoDialog.mostrar(getScene().getWindow(), stock, sel, tipo)) {
            atualizar();
            aoAlterar.run();
        }
    }
}
