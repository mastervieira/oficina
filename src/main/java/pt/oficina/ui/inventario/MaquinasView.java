package pt.oficina.ui.inventario;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import pt.oficina.excel.ExcelExportacao;
import pt.oficina.model.Maquina;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.service.Catalogos;
import pt.oficina.service.ImportacaoService;
import pt.oficina.service.InventarioService;
import pt.oficina.ui.comum.Combos;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.ExcelUi;
import pt.oficina.ui.comum.Formatos;
import pt.oficina.ui.comum.TabelaPesquisavel;

/** Listagem pesquisável de máquinas, com registo e edição. */
public final class MaquinasView extends VBox {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);

    private final InventarioService svc;
    private final ImportacaoService importacao;
    private Catalogos cat = Catalogos.vazio();
    private final TabelaPesquisavel<Maquina> tabela;

    public MaquinasView(InventarioService svc, ImportacaoService importacao) {
        this.svc = svc;
        this.importacao = importacao;
        tabela = new TabelaPesquisavel<>(m -> String.join(" ",
                m.codigo(), m.descricao(), Formatos.texto(m.nSerie()),
                cat.categoria(m.categoriaId()), cat.localizacao(m.localizacaoId()),
                cat.fornecedor(m.fornecedorId()), m.estado().toString()));

        tabela.addColunaFixa("Código", Maquina::codigo, 90);
        tabela.addColuna("Descrição", Maquina::descricao, 240);
        tabela.addColunaFixa("Nº série", m -> Formatos.texto(m.nSerie()), 110);
        tabela.addColuna("Categoria", m -> cat.categoria(m.categoriaId()), 140);
        tabela.addColuna("Localização", m -> cat.localizacao(m.localizacaoId()), 230);
        tabela.addColuna("Fornecedor", m -> cat.fornecedor(m.fornecedorId()), 140);
        tabela.addColunaFixa("Aquisição", m -> m.dataAquisicao() == null ? "" : DATA.format(m.dataAquisicao()), 110);
        tabela.addColunaFixa("Estado", m -> m.estado().toString(), 100);
        tabela.addColunaEditarEliminar(this::abrir, this::eliminar);

        ComboBox<EstadoMaquina> filtroEstado = Combos.opcional(List.of(EstadoMaquina.values()), "Todos os estados");
        filtroEstado.valueProperty().addListener((o, a, sel) ->
                tabela.setFiltroExtra(m -> sel == null || m.estado() == sel));

        Button nova = new Button("Nova máquina");
        nova.setOnAction(e -> abrir(null));
        Button editar = new Button("Editar");
        editar.setOnAction(e -> abrir(tabela.selecionado()));
        editar.disableProperty().bind(tabela.tabela().getSelectionModel().selectedItemProperty().isNull());
        tabela.setAoDuploClique(this::abrir);
        Button importar = new Button("Importar Excel…");
        importar.setOnAction(e -> ExcelUi.importar(getScene().getWindow(), "máquinas", importacao::importarMaquinas, this::atualizar));
        Button exportar = new Button("Exportar Excel…");
        exportar.setOnAction(e -> {
            // cópias: a exportação corre fora do fio da interface e não deve ler a tabela do ecrã
            var itens = tabela.itensVisiveis();
            var c = cat;
            ExcelUi.exportar(getScene().getWindow(), "máquinas", "Exportadas", "maquinas",
                    itens.size(), tabela.totalItens(), destino -> ExcelExportacao.maquinas(destino, itens, c));
        });
        tabela.barra().getChildren().addAll(filtroEstado, nova, editar, importar, exportar);

        VBox.setVgrow(tabela, Priority.ALWAYS);
        getChildren().add(tabela);
        atualizar();
    }

    public void atualizar() {
        Dialogos.tentar(() -> {
            cat = svc.catalogos();
            tabela.setItens(svc.maquinas());
            tabela.tabela().refresh();
        });
    }

    private void eliminar(Maquina m) {
        if (Dialogos.confirmar("Eliminar a máquina " + m.codigo() + " — " + m.descricao()
                + "?\nEsta ação não pode ser desfeita.")
                && Dialogos.tentar(() -> svc.apagarMaquina(m.id()))) {
            atualizar();
        }
    }

    private void abrir(Maquina existente) {
        if (MaquinaDialog.mostrar(getScene().getWindow(), svc, cat, existente)) {
            atualizar();
        }
    }
}
