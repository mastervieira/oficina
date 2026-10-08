package pt.oficina.ui.configuracoes;

import java.sql.SQLException;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import pt.oficina.model.Categoria;
import pt.oficina.model.PlanoManutencao;
import pt.oficina.service.ManutencaoService;
import pt.oficina.service.ValidacaoException;
import pt.oficina.ui.comum.Dialogos;

/** Criação/edição de um plano de manutenção de um tipo de máquina (tarefa + periodicidade em meses). */
final class PlanoDialog {

    private PlanoDialog() {
    }

    /**
     * @param tipo      o tipo de máquina (categoria) a que o plano pertence; não se muda
     * @param existente o plano a editar, ou null para criar
     * @return true se o plano foi gravado
     */
    static boolean mostrar(Window dono, ManutencaoService svc, Categoria tipo, PlanoManutencao existente) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initOwner(dono);
        dlg.setTitle(existente == null ? "Novo plano de manutenção" : "Editar plano de manutenção");
        ButtonType guardar = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(guardar, ButtonType.CANCEL);

        TextField tarefa = new TextField();
        tarefa.setPromptText("ex.: Lubrificação geral");
        TextField meses = new TextField();
        meses.setPrefColumnCount(5);
        Label nota = new Label("Vale para todas as máquinas deste tipo. A próxima data conta-se por máquina: última "
                + "intervenção dessa máquina ligada a este plano + a periodicidade.");
        nota.setWrapText(true);
        nota.setMaxWidth(440);
        Label erro = new Label();
        erro.setStyle("-fx-text-fill: #b00020;");
        erro.setWrapText(true);

        if (existente != null) {
            tarefa.setText(existente.tarefa());
            meses.setText(String.valueOf(existente.periodicidadeMeses()));
        }

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(15));
        ColumnConstraints rotulos = new ColumnConstraints();
        ColumnConstraints campos = new ColumnConstraints(380);
        campos.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(rotulos, campos);
        int r = 0;
        g.addRow(r++, new Label("Tipo de máquina"), new Label(tipo.nome()));
        g.addRow(r++, new Label("Tarefa *"), tarefa);
        g.addRow(r++, new Label("Periodicidade *"), new HBox(8, meses, new Label("meses (de calendário)")));
        g.add(nota, 0, r++, 2, 1);
        g.add(erro, 0, r, 2, 1);
        dlg.getDialogPane().setContent(g);
        dlg.setOnShown(e -> tarefa.requestFocus());

        boolean[] gravado = {false};
        Button ok = (Button) dlg.getDialogPane().lookupButton(guardar);
        ok.addEventFilter(ActionEvent.ACTION, ev -> {
            try {
                int n;
                try {
                    n = Integer.parseInt(meses.getText().trim());
                } catch (NumberFormatException e) {
                    throw new ValidacaoException("A periodicidade tem de ser um número inteiro de meses.");
                }
                svc.guardarPlano(new PlanoManutencao(existente == null ? null : existente.id(), tipo.id(),
                        tarefa.getText(), n));
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
