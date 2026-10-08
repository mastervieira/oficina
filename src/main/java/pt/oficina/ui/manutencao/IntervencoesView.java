package pt.oficina.ui.manutencao;

import java.util.HashMap;
import java.util.Map;
import javafx.scene.control.Button;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import pt.oficina.excel.ExcelExportacao;
import pt.oficina.model.Intervencao;
import pt.oficina.model.Maquina;
import pt.oficina.model.PlanoManutencao;
import pt.oficina.service.ImportacaoService;
import pt.oficina.service.InventarioService;
import pt.oficina.service.ManutencaoService;
import pt.oficina.service.StockService;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.ExcelUi;
import pt.oficina.ui.comum.Formatos;
import pt.oficina.ui.comum.TabelaPesquisavel;
import pt.oficina.ui.comum.TextosImportacao;

/** Histórico de intervenções (só se acrescenta). */
public final class IntervencoesView extends VBox {

    private final ManutencaoService svc;
    private final StockService stock;
    private final InventarioService inventario;
    private final Runnable aoAlterar;
    private final Map<Long, Maquina> maquinas = new HashMap<>();
    private final Map<Long, PlanoManutencao> planos = new HashMap<>();
    private final TabelaPesquisavel<Intervencao> tabela;

    public IntervencoesView(ManutencaoService svc, StockService stock, InventarioService inventario,
            ImportacaoService importacao, Runnable aoAlterar) {
        this.svc = svc;
        this.stock = stock;
        this.inventario = inventario;
        this.aoAlterar = aoAlterar;
        tabela = new TabelaPesquisavel<>(i -> codigo(i) + " " + descricaoMaquina(i) + " " + i.tipo() + " "
                + tarefa(i) + " " + Formatos.texto(i.descricao()));

        tabela.addColuna("Data", i -> Formatos.data(i.dataInterv()), 110);
        tabela.addColuna("Máquina", this::codigo, 90);
        tabela.addColuna("Descrição", this::descricaoMaquina, 200);
        tabela.addColuna("Tipo", i -> i.tipo().toString(), 90);
        tabela.addColuna("Plano", this::tarefa, 200);
        tabela.addColuna("Descrição da intervenção", i -> Formatos.texto(i.descricao()), 280);
        tabela.addColuna("Custo", i -> i.custo() == null ? "" : Formatos.numero(i.custo()) + " €", 90);

        Button nova = new Button("Nova intervenção");
        nova.setOnAction(e -> registar());
        Button importar = new Button("Importar Excel…");
        importar.setOnAction(e -> ExcelUi.importar(getScene().getWindow(), "intervenções",
                importacao::importarIntervencoes, aoAlterar, TextosImportacao.Rotulos.INTERVENCOES));
        Button exportar = new Button("Exportar Excel…");
        exportar.setOnAction(e -> {
            // cópias: a exportação corre fora do fio da interface e não deve ler a tabela do ecrã
            var itens = tabela.itensVisiveis();
            var m = Map.copyOf(maquinas);
            var p = Map.copyOf(planos);
            ExcelUi.exportar(getScene().getWindow(), "intervenções", "Exportadas", "intervencoes",
                    itens.size(), tabela.totalItens(), destino -> ExcelExportacao.intervencoes(destino, itens, m, p));
        });
        tabela.barra().getChildren().addAll(nova, importar, exportar);

        VBox.setVgrow(tabela, Priority.ALWAYS);
        getChildren().add(tabela);
        atualizar();
    }

    public void atualizar() {
        Dialogos.tentar(() -> {
            maquinas.clear();
            planos.clear();
            for (Maquina m : inventario.maquinas()) {
                maquinas.put(m.id(), m);
            }
            svc.planosDeManutencao().forEach(p -> planos.put(p.id(), p));
            tabela.setItens(svc.intervencoes());
            tabela.tabela().refresh();
        });
    }

    private String codigo(Intervencao i) {
        Maquina m = maquinas.get(i.maquinaId());
        return m == null ? "" : m.codigo();
    }

    private String descricaoMaquina(Intervencao i) {
        Maquina m = maquinas.get(i.maquinaId());
        return m == null ? "" : m.descricao();
    }

    private String tarefa(Intervencao i) {
        PlanoManutencao p = i.planoId() == null ? null : planos.get(i.planoId());
        return p == null ? "" : p.tarefa();
    }

    private void registar() {
        boolean[] gravado = {false};
        boolean ok = Dialogos.tentar(() -> gravado[0] = IntervencaoDialog.mostrar(
                getScene().getWindow(), svc, stock, svc.maquinasParaManutencao(),
                svc.planosDeManutencao(), null, null));
        if (ok && gravado[0]) {
            atualizar();
            aoAlterar.run();
        }
    }
}
