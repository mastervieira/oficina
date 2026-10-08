package pt.oficina.ui.configuracoes;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import pt.oficina.model.Categoria;
import pt.oficina.model.PlanoManutencao;
import pt.oficina.service.InventarioService;
import pt.oficina.service.ManutencaoService;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.TabelaPesquisavel;

/**
 * Planos de manutenção por tipo de máquina: à esquerda os tipos (categorias de máquinas), à direita os planos do
 * tipo escolhido. Todas as máquinas de um tipo têm os planos desse tipo.
 */
final class PlanosTipoView extends HBox {

    private final ManutencaoService manutencao;
    private final InventarioService inventario;
    private final ListView<Categoria> tipos = new ListView<>();
    private final Label titulo = new Label();
    private final Label aplicaSe = new Label();
    private final Button novo = new Button("Novo plano");
    private final TabelaPesquisavel<PlanoManutencao> tabela;
    private Map<Long, Long> planosPorTipo = Map.of();
    private Map<Long, Long> maquinasPorTipo = Map.of();

    PlanosTipoView(ManutencaoService manutencao, InventarioService inventario) {
        super(10);
        this.manutencao = manutencao;
        this.inventario = inventario;
        setPadding(new Insets(10));

        Label rotuloTipos = new Label("Tipos de máquina");
        rotuloTipos.setStyle("-fx-font-weight: bold;");
        tipos.setPrefWidth(260);
        tipos.setMinWidth(200);
        tipos.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Categoria tipo, boolean empty) {
                super.updateItem(tipo, empty);
                if (empty || tipo == null) {
                    setText(null);
                } else {
                    long n = planosPorTipo.getOrDefault(tipo.id(), 0L);
                    setText(tipo.nome() + "   ·   " + n + (n == 1 ? " plano" : " planos"));
                }
            }
        });
        tipos.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> mostrarPlanos());
        VBox esquerda = new VBox(8, rotuloTipos, tipos);
        VBox.setVgrow(tipos, Priority.ALWAYS);

        titulo.setStyle("-fx-font-weight: bold;");
        tabela = new TabelaPesquisavel<>(p -> p.tarefa());
        tabela.addColuna("Tarefa", PlanoManutencao::tarefa, 300);
        tabela.addColunaFixa("Periodicidade", p -> p.periodicidadeMeses() + (p.periodicidadeMeses() == 1 ? " mês" : " meses"), 130);
        tabela.addColunaEditarEliminar(this::editar, this::remover);
        tabela.setAoDuploClique(this::editar);
        novo.setOnAction(e -> criar());
        tabela.barra().getChildren().add(novo);

        VBox direita = new VBox(6, titulo, aplicaSe, tabela);
        VBox.setVgrow(tabela, Priority.ALWAYS);
        HBox.setHgrow(direita, Priority.ALWAYS);
        getChildren().addAll(esquerda, direita);
        atualizar();
    }

    void atualizar() {
        Categoria escolhido = tipos.getSelectionModel().getSelectedItem();
        Dialogos.tentar(() -> {
            planosPorTipo = manutencao.planosDeManutencao().stream()
                    .collect(Collectors.groupingBy(PlanoManutencao::categoriaId, Collectors.counting()));
            maquinasPorTipo = inventario.maquinas().stream()
                    .collect(Collectors.groupingBy(m -> m.categoriaId(), Collectors.counting()));
            List<Categoria> lista = inventario.catalogos().categoriasMaquina();
            tipos.getItems().setAll(lista);
            if (escolhido != null) {
                lista.stream().filter(t -> t.id().equals(escolhido.id())).findFirst()
                        .ifPresent(t -> tipos.getSelectionModel().select(t));
            }
            tipos.refresh();
        });
        mostrarPlanos();
    }

    private void mostrarPlanos() {
        Categoria tipo = tipos.getSelectionModel().getSelectedItem();
        novo.setDisable(tipo == null);
        if (tipo == null) {
            titulo.setText(tipos.getItems().isEmpty()
                    ? "Ainda não há tipos de máquina. Crie categorias de máquinas em Listas."
                    : "Escolha um tipo de máquina à esquerda.");
            aplicaSe.setText("");
            tabela.setItens(List.of());
            return;
        }
        titulo.setText("Planos de manutenção — " + tipo.nome());
        long n = maquinasPorTipo.getOrDefault(tipo.id(), 0L);
        aplicaSe.setText("Aplicam-se a " + n + (n == 1 ? " máquina" : " máquinas") + " deste tipo.");
        Dialogos.tentar(() -> tabela.setItens(manutencao.planosDoTipo(tipo.id())));
    }

    private void criar() {
        Categoria tipo = tipos.getSelectionModel().getSelectedItem();
        if (tipo != null && PlanoDialog.mostrar(getScene().getWindow(), manutencao, tipo, null)) {
            atualizar();
        }
    }

    private void editar(PlanoManutencao plano) {
        Categoria tipo = tipos.getSelectionModel().getSelectedItem();
        if (tipo != null && PlanoDialog.mostrar(getScene().getWindow(), manutencao, tipo, plano)) {
            atualizar();
        }
    }

    private void remover(PlanoManutencao plano) {
        Categoria tipo = tipos.getSelectionModel().getSelectedItem();
        if (tipo != null && Dialogos.confirmar("Remover o plano «" + plano.tarefa() + "» do tipo " + tipo.nome()
                + "?\nDeixa de existir para todas as máquinas deste tipo.")
                && Dialogos.tentar(() -> manutencao.apagarPlano(plano.id()))) {
            atualizar();
        }
    }
}
