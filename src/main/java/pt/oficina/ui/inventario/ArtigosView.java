package pt.oficina.ui.inventario;

import java.util.Map;
import java.util.stream.Collectors;
import javafx.scene.control.Button;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import pt.oficina.excel.ExcelExportacao;
import pt.oficina.model.Artigo;
import pt.oficina.model.StockArtigo;
import pt.oficina.service.Catalogos;
import pt.oficina.service.ImportacaoService;
import pt.oficina.service.InventarioService;
import pt.oficina.service.StockService;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.ExcelUi;
import pt.oficina.ui.comum.Formatos;
import pt.oficina.ui.comum.TabelaPesquisavel;

/** Listagem pesquisável de artigos, com registo e edição. O stock atual é só informação (calculado). */
public final class ArtigosView extends VBox {

    private final InventarioService svc;
    private final StockService stock;
    private final ImportacaoService importacao;
    private Catalogos cat = Catalogos.vazio();
    private Map<Long, Double> stocks = Map.of();
    private final TabelaPesquisavel<Artigo> tabela;

    public ArtigosView(InventarioService svc, StockService stock, ImportacaoService importacao) {
        this.svc = svc;
        this.stock = stock;
        this.importacao = importacao;
        tabela = new TabelaPesquisavel<>(a -> String.join(" ",
                a.codigo(), a.descricao(), cat.categoria(a.categoriaId()),
                cat.localizacao(a.localizacaoId()), cat.fornecedor(a.fornecedorId())));

        tabela.addColunaFixa("Código", Artigo::codigo, 100);
        tabela.addColuna("Descrição", Artigo::descricao, 280);
        tabela.addColuna("Categoria", a -> cat.categoria(a.categoriaId()), 150);
        tabela.addColuna("Localização", a -> cat.localizacao(a.localizacaoId()), 230);
        tabela.addColuna("Fornecedor", a -> cat.fornecedor(a.fornecedorId()), 150);
        tabela.addColunaFixa("Unidade", Artigo::unidade, 70);
        tabela.addColunaFixa("Stock atual", a -> Formatos.numero(stocks.getOrDefault(a.id(), 0.0)), 90);
        tabela.addColunaFixa("Stock mínimo", a -> Formatos.numero(a.stockMinimo()), 100);
        tabela.addColunaEditarEliminar(this::abrir, this::eliminar);

        Button novo = new Button("Novo artigo");
        novo.setOnAction(e -> abrir(null));
        Button editar = new Button("Editar");
        editar.setOnAction(e -> abrir(tabela.selecionado()));
        editar.disableProperty().bind(tabela.tabela().getSelectionModel().selectedItemProperty().isNull());
        tabela.setAoDuploClique(this::abrir);
        Button importar = new Button("Importar Excel…");
        importar.setOnAction(e -> ExcelUi.importar(getScene().getWindow(), "artigos", importacao::importarArtigos, this::atualizar));
        Button exportar = new Button("Exportar Excel…");
        exportar.setOnAction(e -> {
            // cópias: a exportação corre fora do fio da interface e não deve ler a tabela do ecrã
            var itens = tabela.itensVisiveis();
            var s = Map.copyOf(stocks);
            var c = cat;
            ExcelUi.exportar(getScene().getWindow(), "artigos", "Exportados", "artigos",
                    itens.size(), tabela.totalItens(), destino -> ExcelExportacao.artigos(destino, itens, s, c));
        });
        tabela.barra().getChildren().addAll(novo, editar, importar, exportar);

        VBox.setVgrow(tabela, Priority.ALWAYS);
        getChildren().add(tabela);
        atualizar();
    }

    public void atualizar() {
        Dialogos.tentar(() -> {
            cat = svc.catalogos();
            stocks = stock.stockAtual().stream()
                    .collect(Collectors.toMap(s -> s.artigo().id(), StockArtigo::stock));
            tabela.setItens(svc.artigos());
            tabela.tabela().refresh();
        });
    }

    private void eliminar(Artigo a) {
        if (Dialogos.confirmar("Eliminar o artigo " + a.codigo() + " — " + a.descricao()
                + "?\nEsta ação não pode ser desfeita.")
                && Dialogos.tentar(() -> svc.apagarArtigo(a.id()))) {
            atualizar();
        }
    }

    private void abrir(Artigo existente) {
        if (ArtigoDialog.mostrar(getScene().getWindow(), svc, cat, existente)) {
            atualizar();
        }
    }
}
