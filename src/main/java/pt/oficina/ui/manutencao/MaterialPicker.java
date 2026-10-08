package pt.oficina.ui.manutencao;

import java.sql.SQLException;
import java.util.Optional;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import pt.oficina.model.StockArtigo;
import pt.oficina.service.StockService;
import pt.oficina.service.ValidacaoException;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.Formatos;
import pt.oficina.ui.comum.TabelaPesquisavel;

/** Escolha de um artigo (com pesquisa) e quantidade, para o material consumido numa intervenção. */
final class MaterialPicker {

    /** Linha de material a consumir. */
    record LinhaMaterial(StockArtigo artigo, double quantidade) {
        @Override
        public String toString() {
            return artigo.artigo().codigo() + " — " + artigo.artigo().descricao() + ": "
                    + Formatos.numero(quantidade) + " " + artigo.artigo().unidade();
        }
    }

    private MaterialPicker() {
    }

    static Optional<LinhaMaterial> pedir(Window dono, StockService stock) {
        TabelaPesquisavel<StockArtigo> tabela = new TabelaPesquisavel<>(
                s -> s.artigo().codigo() + " " + s.artigo().descricao());
        tabela.addColuna("Código", s -> s.artigo().codigo(), 100);
        tabela.addColuna("Descrição", s -> s.artigo().descricao(), 280);
        tabela.addColuna("Stock", s -> Formatos.numero(s.stock()), 80);
        tabela.addColuna("Unid.", s -> s.artigo().unidade(), 60);
        tabela.setPrefHeight(340);
        tabela.setPrefWidth(620);
        try {
            tabela.setItens(stock.stockAtual());
        } catch (SQLException | RuntimeException e) {
            Dialogos.erro(Dialogos.mensagemDeErro(e));
            return Optional.empty();
        }

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initOwner(dono);
        dlg.setTitle("Material consumido");
        ButtonType adicionar = new ButtonType("Adicionar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(adicionar, ButtonType.CANCEL);

        TextField quantidade = new TextField();
        quantidade.setPromptText("quantidade");
        Label erro = new Label();
        erro.setStyle("-fx-text-fill: #b00020;");
        erro.setWrapText(true);
        VBox conteudo = new VBox(8, tabela, new HBox(10, new Label("Quantidade *"), quantidade), erro);
        conteudo.setPadding(new Insets(5));
        dlg.getDialogPane().setContent(conteudo);

        LinhaMaterial[] escolhida = {null};
        Button ok = (Button) dlg.getDialogPane().lookupButton(adicionar);
        ok.addEventFilter(ActionEvent.ACTION, ev -> {
            try {
                StockArtigo sel = tabela.selecionado();
                if (sel == null) {
                    throw new ValidacaoException("Escolha um artigo na lista.");
                }
                double q = Formatos.lerNumero(quantidade.getText(), "A quantidade");
                if (q <= 0) {
                    throw new ValidacaoException("A quantidade tem de ser superior a zero.");
                }
                if (q > sel.stock() + 1e-9) {
                    throw new ValidacaoException("Stock insuficiente: disponível " + Formatos.numero(sel.stock())
                            + " " + sel.artigo().unidade() + ".");
                }
                escolhida[0] = new LinhaMaterial(sel, q);
            } catch (RuntimeException e) {
                erro.setText(Dialogos.mensagemDeErro(e));
                ev.consume();
            }
        });

        dlg.showAndWait();
        return Optional.ofNullable(escolhida[0]);
    }
}
