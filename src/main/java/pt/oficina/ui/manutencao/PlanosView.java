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
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.Estilos;
import pt.oficina.ui.comum.ExcelUi;
import pt.oficina.ui.comum.Formatos;
import pt.oficina.ui.comum.TabelaPesquisavel;

/**
 * Os planos de cada máquina (os do seu tipo), com a última intervenção e a próxima data. Só de consulta: os planos
 * definem-se por tipo de máquina em Configurações › Planos de manutenção.
 */
public final class PlanosView extends VBox {

    private final ManutencaoService svc;
    private final InventarioService inventario;
    private Catalogos cat = Catalogos.vazio();
    private final TabelaPesquisavel<PlanoDaMaquina> tabela;
    private LocalDate hoje = LocalDate.now();

    public PlanosView(ManutencaoService svc, InventarioService inventario) {
        this.svc = svc;
        this.inventario = inventario;
        tabela = new TabelaPesquisavel<>(p -> p.maquina().codigo() + " " + p.maquina().descricao() + " "
                + cat.categoria(p.maquina().categoriaId()) + " " + p.prazo().plano().tarefa());

        tabela.addColunaFixa("Máquina", p -> p.maquina().codigo(), 90);
        tabela.addColuna("Descrição", p -> p.maquina().descricao(), 220);
        tabela.addColuna("Tipo", p -> cat.categoria(p.maquina().categoriaId()), 150);
        tabela.addColuna("Tarefa", p -> p.prazo().plano().tarefa(), 220);
        tabela.addColunaFixa("Periodicidade", p -> p.prazo().plano().periodicidadeMeses()
                + (p.prazo().plano().periodicidadeMeses() == 1 ? " mês" : " meses"), 110);
        tabela.addColunaFixa("Última", p -> Formatos.data(p.prazo().ultima()), 110);
        tabela.addColunaFixa("Próxima", p -> Formatos.data(p.prazo().proxima()), 110);
        tabela.addColunaFixa("Situação", p -> Prazos.situacao(p.prazo(), hoje), 200);

        tabela.tabela().setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(PlanoDaMaquina item, boolean empty) {
                super.updateItem(item, empty);
                Estilos.classe(this, "linha-alerta", !empty && item != null && item.prazo().vencido(hoje));
            }
        });

        Label nota = new Label("Os planos definem-se por tipo de máquina em Configurações › Planos de manutenção.");
        Button exportar = new Button("Exportar Excel…");
        exportar.setOnAction(e -> {
            // cópias: a exportação corre fora do fio da interface e não deve ler a tabela do ecrã
            var itens = tabela.itensVisiveis();
            var h = hoje;
            var c = cat;
            ExcelUi.exportar(getScene().getWindow(), "planos", "Exportados", "planos-maquinas",
                    itens.size(), tabela.totalItens(), destino -> ExcelExportacao.planos(destino, "Planos", itens, h, c));
        });
        tabela.barra().getChildren().addAll(exportar, nota);

        VBox.setVgrow(tabela, Priority.ALWAYS);
        getChildren().add(tabela);
        atualizar();
    }

    public void atualizar() {
        Dialogos.tentar(() -> {
            hoje = LocalDate.now();
            cat = inventario.catalogos();
            tabela.setItens(svc.planosDasMaquinas());
            tabela.tabela().refresh();
        });
    }
}
