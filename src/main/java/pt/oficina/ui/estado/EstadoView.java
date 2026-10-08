package pt.oficina.ui.estado;

import java.time.LocalDate;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableRow;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import pt.oficina.excel.ExcelExportacao;
import pt.oficina.model.HistoricoEstado;
import pt.oficina.model.Maquina;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.service.Catalogos;
import pt.oficina.service.EstadoMaquinaService;
import pt.oficina.service.ImportacaoService;
import pt.oficina.service.InventarioService;
import pt.oficina.ui.comum.Combos;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.Estilos;
import pt.oficina.ui.comum.ExcelUi;
import pt.oficina.ui.comum.Formatos;
import pt.oficina.ui.comum.TabelaPesquisavel;
import pt.oficina.ui.comum.TextosImportacao;

/** Módulo 4: estado das máquinas (em cima) e histórico da máquina selecionada (em baixo). */
public final class EstadoView extends SplitPane {

    private final EstadoMaquinaService svc;
    private final InventarioService inventario;
    private final Runnable aoAlterar;
    private Catalogos cat = Catalogos.vazio();
    private Map<Long, LocalDate> desde = Map.of();
    private final TabelaPesquisavel<Maquina> maquinas;
    private final TabelaPesquisavel<HistoricoEstado> historico;
    private final Label tituloHistorico = new Label("Histórico");

    /** @param aoAlterar chamado depois de mudar um estado (para atualizar os outros módulos) */
    public EstadoView(EstadoMaquinaService svc, InventarioService inventario, ImportacaoService importacao,
            Runnable aoAlterar) {
        super();
        this.svc = svc;
        this.inventario = inventario;
        this.aoAlterar = aoAlterar;
        setOrientation(Orientation.VERTICAL);

        maquinas = new TabelaPesquisavel<>(m -> String.join(" ", m.codigo(), m.descricao(),
                cat.categoria(m.categoriaId()), cat.localizacao(m.localizacaoId()), m.estado().toString()));
        maquinas.addColuna("Código", Maquina::codigo, 90);
        maquinas.addColuna("Descrição", Maquina::descricao, 260);
        maquinas.addColuna("Categoria", m -> cat.categoria(m.categoriaId()), 150);
        maquinas.addColuna("Localização", m -> cat.localizacao(m.localizacaoId()), 230);
        maquinas.addColuna("Estado", m -> m.estado().toString(), 110);
        maquinas.addColuna("Desde", m -> Formatos.data(desde.get(m.id())), 120);
        maquinas.tabela().setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(Maquina item, boolean empty) {
                super.updateItem(item, empty);
                EstadoMaquina estado = empty || item == null ? null : item.estado();
                Estilos.classe(this, "linha-inoperativo", estado == EstadoMaquina.INOPERATIVO);
                Estilos.classe(this, "linha-manutencao", estado == EstadoMaquina.MANUTENCAO);
                Estilos.classe(this, "linha-abatido", estado == EstadoMaquina.ABATIDO);
            }
        });

        ComboBox<EstadoMaquina> filtro = Combos.opcional(List.of(EstadoMaquina.values()), "Todos os estados");
        filtro.valueProperty().addListener((o, a, sel) -> maquinas.setFiltroExtra(m -> sel == null || m.estado() == sel));
        Button alterar = new Button("Alterar estado…");
        alterar.setOnAction(e -> alterar(maquinas.selecionado()));
        alterar.disableProperty().bind(maquinas.tabela().getSelectionModel().selectedItemProperty().isNull());
        maquinas.setAoDuploClique(this::alterar);
        Button importar = new Button("Importar Excel…");
        importar.setOnAction(e -> ExcelUi.importar(getScene().getWindow(), "estados", importacao::importarEstados,
                () -> {
                    atualizar();
                    aoAlterar.run();
                }, TextosImportacao.Rotulos.ESTADOS));
        Button exportar = new Button("Exportar Excel…");
        exportar.setOnAction(e -> {
            // cópias: a exportação corre fora do fio da interface e não deve ler a tabela do ecrã
            var itens = maquinas.itensVisiveis();
            var d = Map.copyOf(desde);
            var c = cat;
            ExcelUi.exportar(getScene().getWindow(), "máquinas", "Exportadas", "estados",
                    itens.size(), maquinas.totalItens(), destino -> ExcelExportacao.estados(destino, itens, d, c));
        });
        maquinas.barra().getChildren().addAll(filtro, alterar, importar, exportar);

        historico = new TabelaPesquisavel<>(h -> h.estado() + " " + Formatos.texto(h.motivo()));
        historico.addColuna("Data", h -> Formatos.data(h.dataEstado()), 120);
        historico.addColuna("Estado", h -> h.estado().toString(), 110);
        historico.addColuna("Motivo", h -> Formatos.texto(h.motivo()), 500);
        tituloHistorico.setStyle("-fx-font-weight: bold;");
        historico.barra().getChildren().add(0, tituloHistorico);
        Button exportarHistorico = new Button("Exportar histórico de todas…");
        exportarHistorico.setOnAction(e -> exportarHistorico());
        historico.barra().getChildren().add(exportarHistorico);
        historico.setMinHeight(150);

        maquinas.tabela().getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> carregarHistorico(sel));

        VBox.setVgrow(maquinas, Priority.ALWAYS);
        getItems().addAll(maquinas, historico);
        setDividerPositions(0.62);
        setPadding(new Insets(0));
        atualizar();
    }

    public void atualizar() {
        Maquina selecionada = maquinas.selecionado();
        Long idSelecionado = selecionada == null ? null : selecionada.id();
        Dialogos.tentar(() -> {
            cat = inventario.catalogos();
            desde = svc.desde();
            maquinas.setItens(inventario.maquinas());
            maquinas.tabela().refresh();
        });
        if (idSelecionado != null) {
            maquinas.tabela().getItems().stream()
                    .filter(m -> m.id().equals(idSelecionado))
                    .findFirst()
                    .ifPresent(m -> maquinas.tabela().getSelectionModel().select(m));
        }
        carregarHistorico(maquinas.selecionado());
    }

    private void carregarHistorico(Maquina m) {
        if (m == null) {
            tituloHistorico.setText("Histórico (selecione uma máquina)");
            historico.setItens(List.of());
            return;
        }
        tituloHistorico.setText("Histórico de " + m);
        Dialogos.tentar(() -> historico.setItens(svc.historico(m.id())));
    }

    /** O histórico completo (de todas as máquinas), qualquer que seja a máquina selecionada ou o filtro. */
    private void exportarHistorico() {
        Dialogos.tentar(() -> {
            var todo = svc.historicoCompleto();
            Map<Long, Maquina> porId = new HashMap<>();
            inventario.maquinas().forEach(m -> porId.put(m.id(), m));
            ExcelUi.exportar(getScene().getWindow(), "mudanças de estado", "Exportadas", "historico-estados",
                    todo.size(), todo.size(), destino -> ExcelExportacao.historico(destino, todo, porId));
        });
    }

    private void alterar(Maquina m) {
        if (m != null && EstadoDialog.mostrar(getScene().getWindow(), svc, m)) {
            atualizar();
            aoAlterar.run();
        }
    }
}
