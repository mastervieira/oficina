package pt.oficina.ui.stock;

import java.util.HashMap;
import java.util.Map;
import javafx.scene.control.Button;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import pt.oficina.excel.ExcelExportacao;
import pt.oficina.model.Artigo;
import pt.oficina.model.MovimentoStock;
import pt.oficina.model.StockArtigo;
import pt.oficina.service.ImportacaoService;
import pt.oficina.service.StockService;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.ExcelUi;
import pt.oficina.ui.comum.Formatos;
import pt.oficina.ui.comum.TabelaPesquisavel;
import pt.oficina.ui.comum.TextosImportacao;

/** Histórico de todos os movimentos (só leitura: as correções fazem-se com um movimento contrário). */
public final class MovimentosView extends VBox {

    private final StockService stock;
    private final Map<Long, Artigo> artigos = new HashMap<>();
    private final TabelaPesquisavel<MovimentoStock> tabela;

    public MovimentosView(StockService stock, ImportacaoService importacao) {
        this.stock = stock;
        tabela = new TabelaPesquisavel<>(m -> String.join(" ",
                codigo(m), descricao(m), m.tipo().toString(), Formatos.texto(m.nota()), m.dataMov().toString()));

        tabela.addColuna("Data", m -> Formatos.data(m.dataMov()), 120);
        tabela.addColuna("Código", this::codigo, 100);
        tabela.addColuna("Artigo", this::descricao, 260);
        tabela.addColuna("Tipo", m -> m.tipo().toString(), 80);
        tabela.addColuna("Quantidade", m -> Formatos.numero(m.quantidade()) + " " + unidade(m), 110);
        tabela.addColuna("Nota", m -> Formatos.texto(m.nota()), 300);

        Button importar = new Button("Importar Excel…");
        importar.setOnAction(e -> ExcelUi.importar(getScene().getWindow(), "movimentos", importacao::importarMovimentos,
                this::atualizar, TextosImportacao.Rotulos.MOVIMENTOS));
        Button exportar = new Button("Exportar Excel…");
        exportar.setOnAction(e -> {
            // cópias: a exportação corre fora do fio da interface e não deve ler a tabela do ecrã
            var itens = tabela.itensVisiveis();
            var a = Map.copyOf(artigos);
            ExcelUi.exportar(getScene().getWindow(), "movimentos", "Exportados", "movimentos",
                    itens.size(), tabela.totalItens(), destino -> ExcelExportacao.movimentos(destino, itens, a));
        });
        tabela.barra().getChildren().addAll(importar, exportar);

        VBox.setVgrow(tabela, Priority.ALWAYS);
        getChildren().add(tabela);
        atualizar();
    }

    public void atualizar() {
        Dialogos.tentar(() -> {
            artigos.clear();
            for (StockArtigo s : stock.stockAtual()) {
                artigos.put(s.artigo().id(), s.artigo());
            }
            tabela.setItens(stock.movimentos(null));
            tabela.tabela().refresh();
        });
    }

    private String codigo(MovimentoStock m) {
        Artigo a = artigos.get(m.artigoId());
        return a == null ? "" : a.codigo();
    }

    private String descricao(MovimentoStock m) {
        Artigo a = artigos.get(m.artigoId());
        return a == null ? "" : a.descricao();
    }

    private String unidade(MovimentoStock m) {
        Artigo a = artigos.get(m.artigoId());
        return a == null ? "" : a.unidade();
    }
}
