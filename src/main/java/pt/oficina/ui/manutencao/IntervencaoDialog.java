package pt.oficina.ui.manutencao;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import pt.oficina.model.ConsumoMaterial;
import pt.oficina.model.Intervencao;
import pt.oficina.model.Maquina;
import pt.oficina.model.PlanoManutencao;
import pt.oficina.model.enums.TipoIntervencao;
import pt.oficina.service.ManutencaoService;
import pt.oficina.service.StockService;
import pt.oficina.service.ValidacaoException;
import pt.oficina.ui.comum.CampoData;
import pt.oficina.ui.comum.Combos;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.Formatos;
import pt.oficina.ui.manutencao.MaterialPicker.LinhaMaterial;

/** Registo de uma intervenção, com o material consumido (gravado como SAIDA de stock com nota). */
final class IntervencaoDialog {

    private IntervencaoDialog() {
    }

    /**
     * @param preMaquina máquina já escolhida (fica fixa), ou null para escolher na lista
     * @param prePlano   plano a cumprir (implica intervenção preventiva), ou null
     * @return true se a intervenção foi gravada
     */
    static boolean mostrar(Window dono, ManutencaoService svc, StockService stock, List<Maquina> maquinas,
            List<PlanoManutencao> planos, Maquina preMaquina, PlanoManutencao prePlano) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initOwner(dono);
        dlg.setTitle("Registar intervenção");
        ButtonType guardar = new ButtonType("Registar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(guardar, ButtonType.CANCEL);

        ComboBox<Maquina> maquina = Combos.obrigatoria(maquinas, "Escolher…");
        ComboBox<TipoIntervencao> tipo = Combos.obrigatoria(List.of(TipoIntervencao.values()), "Escolher…");
        ComboBox<PlanoManutencao> plano = Combos.opcional(List.of(), "(nenhum)");
        DatePicker data = CampoData.criar(LocalDate.now());
        TextField descricao = new TextField();
        TextField custo = new TextField();
        custo.setPromptText("€ (opcional)");
        ListView<LinhaMaterial> material = new ListView<>();
        material.setPrefHeight(100);
        Label erro = new Label();
        erro.setStyle("-fx-text-fill: #b00020;");
        erro.setWrapText(true);

        // O plano só se aplica a intervenções preventivas ou programadas, e é escolhido entre os planos do tipo da máquina.
        Runnable refrescarPlanos = () -> {
            Maquina m = maquina.getValue();
            boolean preventiva = tipo.getValue() != null && tipo.getValue().cumprePlano();
            PlanoManutencao atual = plano.getValue();
            List<PlanoManutencao> daMaquina = m == null || !preventiva ? List.of()
                    : planos.stream().filter(p -> p.categoriaId() == m.categoriaId()).toList();
            List<PlanoManutencao> itens = new ArrayList<>();
            itens.add(null);
            itens.addAll(daMaquina);
            plano.getItems().setAll(itens);
            plano.setValue(atual != null && daMaquina.contains(atual) ? atual : null);
            plano.setDisable(daMaquina.isEmpty());
        };
        maquina.valueProperty().addListener((o, a, b) -> refrescarPlanos.run());
        tipo.valueProperty().addListener((o, a, b) -> refrescarPlanos.run());

        if (preMaquina != null) {
            Combos.selecionar(maquina, Maquina::id, preMaquina.id());
            maquina.setDisable(true);
        }
        tipo.setValue(prePlano != null ? TipoIntervencao.PREVENTIVA : TipoIntervencao.CORRETIVA);
        if (prePlano != null) {
            Combos.selecionar(plano, PlanoManutencao::id, prePlano.id());
        }

        Button addMaterial = new Button("Adicionar material…");
        addMaterial.setOnAction(e -> MaterialPicker.pedir(dlg.getDialogPane().getScene().getWindow(), stock)
                .ifPresent(l -> material.getItems().add(l)));
        Button removerMaterial = new Button("Remover");
        removerMaterial.disableProperty().bind(material.getSelectionModel().selectedItemProperty().isNull());
        removerMaterial.setOnAction(e -> material.getItems().remove(material.getSelectionModel().getSelectedItem()));

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(15));
        ColumnConstraints rotulos = new ColumnConstraints();
        ColumnConstraints campos = new ColumnConstraints(380);
        campos.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(rotulos, campos);
        int r = 0;
        g.addRow(r++, new Label("Máquina *"), maquina);
        g.addRow(r++, new Label("Tipo *"), tipo);
        g.addRow(r++, new Label("Plano cumprido"), plano);
        g.addRow(r++, new Label("Data *"), data);
        g.addRow(r++, new Label("Descrição"), descricao);
        g.addRow(r++, new Label("Custo"), custo);
        g.addRow(r++, new Label("Material"), new VBox(5, material, new HBox(8, addMaterial, removerMaterial)));
        g.add(erro, 0, r, 2, 1);
        dlg.getDialogPane().setContent(g);

        boolean[] gravado = {false};
        Button ok = (Button) dlg.getDialogPane().lookupButton(guardar);
        ok.addEventFilter(ActionEvent.ACTION, ev -> {
            try {
                Maquina m = maquina.getValue();
                if (m == null) {
                    throw new ValidacaoException("A máquina é obrigatória.");
                }
                if (tipo.getValue() == null) {
                    throw new ValidacaoException("O tipo é obrigatório.");
                }
                PlanoManutencao p = plano.getValue();
                String textoCusto = custo.getText();
                Double valorCusto = textoCusto == null || textoCusto.isBlank()
                        ? null : Formatos.lerNumero(textoCusto, "O custo");
                List<ConsumoMaterial> consumos = material.getItems().stream()
                        .map(l -> new ConsumoMaterial(l.artigo().artigo().id(), l.quantidade()))
                        .toList();
                svc.registarIntervencao(new Intervencao(null, m.id(), p == null ? null : p.id(), CampoData.ler(data, "A data"),
                        tipo.getValue(), descricao.getText(), valorCusto), consumos);
                gravado[0] = true;
            } catch (SQLException | RuntimeException e) {
                erro.setText(Dialogos.mensagemDeErro(e));
                ev.consume();
            }
        });

        dlg.showAndWait();
        return gravado[0];
    }
}
