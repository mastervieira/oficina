package pt.oficina.ui.manutencao;

import java.time.LocalDate;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableRow;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import pt.oficina.excel.ExcelExportacao;
import pt.oficina.model.PlanoDaMaquina;
import pt.oficina.service.Catalogos;
import pt.oficina.service.InventarioService;
import pt.oficina.service.ManutencaoService;
import pt.oficina.service.Prazos;
import pt.oficina.service.StockService;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.Estilos;
import pt.oficina.ui.comum.ExcelUi;
import pt.oficina.ui.comum.Formatos;
import pt.oficina.ui.comum.TabelaPesquisavel;

/** Itinerário: manutenções vencidas ou a vencer nos próximos 30 dias, só de máquinas ATIVO. */
public final class ItinerarioView extends VBox {

    private final ManutencaoService svc;
    private final StockService stock;
    private final InventarioService inventario;
    private final Runnable aoAlterar;
    private final TabelaPesquisavel<PlanoDaMaquina> tabela;
    private final Label resumo = new Label();
    private Catalogos cat = Catalogos.vazio();
    private LocalDate hoje = LocalDate.now();

    /** @param aoAlterar chamado depois de registar uma intervenção (para atualizar os outros separadores) */
    public ItinerarioView(ManutencaoService svc, StockService stock, InventarioService inventario,
            Runnable aoAlterar) {
        this.svc = svc;
        this.stock = stock;
        this.inventario = inventario;
        this.aoAlterar = aoAlterar;
        tabela = new TabelaPesquisavel<>(i -> i.maquina().codigo() + " " + i.maquina().descricao() + " "
                + i.prazo().plano().tarefa());

        tabela.addColunaFixa("Situação", i -> Prazos.situacao(i.prazo(), hoje), 200);
        tabela.addColuna("Máquina", i -> i.maquina().codigo(), 90);
        tabela.addColuna("Descrição", i -> i.maquina().descricao(), 220);
        tabela.addColuna("Tarefa", i -> i.prazo().plano().tarefa(), 240);
        tabela.addColuna("Última", i -> Formatos.data(i.prazo().ultima()), 110);
        tabela.addColuna("Próxima", i -> Formatos.data(i.prazo().proxima()), 110);
        tabela.addColuna("Prazo", i -> Prazos.prazo(i.prazo(), hoje), 140);

        tabela.tabela().setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(PlanoDaMaquina item, boolean empty) {
                super.updateItem(item, empty);
                Estilos.classe(this, "linha-alerta", !empty && item != null && item.prazo().vencido(hoje));
            }
        });

        Button registar = new Button("Registar intervenção");
        registar.setOnAction(e -> registar(tabela.selecionado()));
        registar.disableProperty().bind(tabela.tabela().getSelectionModel().selectedItemProperty().isNull());
        tabela.setAoDuploClique(this::registar);
        Button atualizar = new Button("Atualizar");
        atualizar.setOnAction(e -> atualizar());
        Button exportar = new Button("Exportar Excel…");
        exportar.setOnAction(e -> {
            // cópias: a exportação corre fora do fio da interface e não deve ler a tabela do ecrã
            var itens = tabela.itensVisiveis();
            var h = hoje;
            var c = cat;
            ExcelUi.exportar(getScene().getWindow(), "linhas do itinerário", "Exportadas", "itinerario",
                    itens.size(), tabela.totalItens(), destino -> ExcelExportacao.planos(destino, "Itinerário", itens, h, c));
        });
        tabela.barra().getChildren().addAll(registar, atualizar, exportar, resumo);

        VBox.setVgrow(tabela, Priority.ALWAYS);
        getChildren().add(tabela);
        atualizar();
    }

    public void atualizar() {
        Dialogos.tentar(() -> {
            hoje = LocalDate.now();
            cat = inventario.catalogos();
            var itens = svc.itinerario(hoje);
            tabela.setItens(itens);
            tabela.tabela().refresh();
            long vencidas = itens.stream().filter(i -> i.prazo().vencido(hoje)).count();
            resumo.setText(itens.size() + " no itinerário (" + vencidas + " vencidas), até "
                    + Formatos.data(hoje.plusDays(ManutencaoService.JANELA_DIAS)));
        });
    }

    private void registar(PlanoDaMaquina item) {
        if (item == null) {
            return;
        }
        boolean[] gravado = {false};
        boolean ok = Dialogos.tentar(() -> gravado[0] = IntervencaoDialog.mostrar(
                getScene().getWindow(), svc, stock, svc.maquinasParaManutencao(),
                svc.planosDeManutencao(), item.maquina(), item.prazo().plano()));
        if (ok && gravado[0]) {
            atualizar();
            aoAlterar.run();
        }
    }
}
