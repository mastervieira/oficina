package pt.oficina.ui.stock;

import java.sql.SQLException;
import java.time.LocalDate;
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
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import pt.oficina.model.StockArtigo;
import pt.oficina.model.enums.TipoMovimento;
import pt.oficina.service.StockService;
import pt.oficina.service.ValidacaoException;
import pt.oficina.ui.comum.CampoData;
import pt.oficina.ui.comum.Combos;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.Formatos;

/** Registo de um movimento de stock para o artigo selecionado. */
final class MovimentoDialog {

    private MovimentoDialog() {
    }

    /** @return true se o movimento foi gravado */
    static boolean mostrar(Window dono, StockService svc, StockArtigo alvo, TipoMovimento tipoInicial) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initOwner(dono);
        dlg.setTitle("Movimento de stock");
        ButtonType guardar = new ButtonType("Registar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(guardar, ButtonType.CANCEL);

        String unidade = alvo.artigo().unidade();
        Label artigo = new Label(alvo.artigo().codigo() + " — " + alvo.artigo().descricao());
        Label atual = new Label(Formatos.numero(alvo.stock()) + " " + unidade
                + "   (mínimo " + Formatos.numero(alvo.artigo().stockMinimo()) + ")");
        ComboBox<TipoMovimento> tipo = Combos.obrigatoria(List.of(TipoMovimento.values()), "Escolher…");
        tipo.setValue(tipoInicial);
        DatePicker data = CampoData.criar(LocalDate.now());
        TextField quantidade = new TextField();
        quantidade.setPromptText("em " + unidade);
        TextField nota = new TextField();
        nota.setPromptText("ex.: fornecedor, peça, intervenção");
        Label erro = new Label();
        erro.setStyle("-fx-text-fill: #b00020;");
        erro.setWrapText(true);

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(15));
        ColumnConstraints rotulos = new ColumnConstraints();
        ColumnConstraints campos = new ColumnConstraints(320);
        campos.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(rotulos, campos);
        int r = 0;
        g.addRow(r++, new Label("Artigo"), artigo);
        g.addRow(r++, new Label("Stock atual"), atual);
        g.addRow(r++, new Label("Tipo *"), tipo);
        g.addRow(r++, new Label("Data *"), data);
        g.addRow(r++, new Label("Quantidade *"), quantidade);
        g.addRow(r++, new Label("Nota"), nota);
        g.add(erro, 0, r, 2, 1);
        dlg.getDialogPane().setContent(g);
        dlg.setOnShown(e -> quantidade.requestFocus());

        boolean[] gravado = {false};
        Button ok = (Button) dlg.getDialogPane().lookupButton(guardar);
        ok.addEventFilter(ActionEvent.ACTION, ev -> {
            try {
                String texto = quantidade.getText();
                if (texto == null || texto.isBlank()) {
                    throw new ValidacaoException("A quantidade é obrigatória.");
                }
                svc.registar(alvo.artigo().id(), tipo.getValue(), CampoData.ler(data, "A data"),
                        Formatos.lerNumero(texto, "A quantidade"), nota.getText());
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
