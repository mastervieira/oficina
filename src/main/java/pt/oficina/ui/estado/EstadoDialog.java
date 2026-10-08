package pt.oficina.ui.estado;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import pt.oficina.model.Maquina;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.service.EstadoMaquinaService;
import pt.oficina.ui.comum.CampoData;
import pt.oficina.ui.comum.Combos;
import pt.oficina.ui.comum.Dialogos;

/** Mudança de estado de uma máquina (grava também uma linha no histórico). */
final class EstadoDialog {

    private EstadoDialog() {
    }

    /** @return true se o estado foi alterado */
    static boolean mostrar(Window dono, EstadoMaquinaService svc, Maquina maquina) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initOwner(dono);
        dlg.setTitle("Alterar estado");
        ButtonType guardar = new ButtonType("Alterar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(guardar, ButtonType.CANCEL);

        ComboBox<EstadoMaquina> novo = Combos.obrigatoria(
                Arrays.stream(EstadoMaquina.values()).filter(e -> e != maquina.estado()).toList(), "Escolher…");
        DatePicker data = CampoData.criar(LocalDate.now());
        TextField motivo = new TextField();
        motivo.setPromptText("ex.: fuso avariado, reparação concluída");
        Label erro = new Label();
        erro.setStyle("-fx-text-fill: #b00020;");
        erro.setWrapText(true);

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(15));
        ColumnConstraints rotulos = new ColumnConstraints();
        ColumnConstraints campos = new ColumnConstraints(340);
        campos.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(rotulos, campos);
        int r = 0;
        g.addRow(r++, new Label("Máquina"), new Label(maquina.toString()));
        g.addRow(r++, new Label("Estado atual"), new Label(maquina.estado().toString()));
        g.addRow(r++, new Label("Novo estado *"), novo);
        g.addRow(r++, new Label("Data *"), data);
        g.addRow(r++, new Label("Motivo *"), motivo);
        g.add(erro, 0, r, 2, 1);
        dlg.getDialogPane().setContent(g);

        boolean[] gravado = {false};
        Button ok = (Button) dlg.getDialogPane().lookupButton(guardar);
        ok.addEventFilter(ActionEvent.ACTION, ev -> {
            try {
                svc.mudarEstado(maquina.id(), novo.getValue(), CampoData.ler(data, "A data"), motivo.getText());
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
