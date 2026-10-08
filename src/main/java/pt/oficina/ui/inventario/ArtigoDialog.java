package pt.oficina.ui.inventario;

import java.sql.SQLException;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import pt.oficina.model.Artigo;
import pt.oficina.model.Categoria;
import pt.oficina.model.Fornecedor;
import pt.oficina.model.Localizacao;
import pt.oficina.model.Unidades;
import pt.oficina.service.Catalogos;
import pt.oficina.service.InventarioService;
import pt.oficina.service.ValidacaoException;
import pt.oficina.ui.comum.CampoImagem;
import pt.oficina.ui.comum.Combos;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.Formatos;

/** Formulário de registo/edição de artigo. A imagem (foto) do artigo é o último campo. */
final class ArtigoDialog {

    private ArtigoDialog() {
    }

    /** @return true se o artigo foi gravado */
    static boolean mostrar(Window dono, InventarioService svc, Catalogos cat, Artigo existente) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initOwner(dono);
        dlg.setTitle(existente == null ? "Novo artigo" : "Editar artigo");
        ButtonType guardar = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(guardar, ButtonType.CANCEL);

        TextField codigo = new TextField();
        TextField descricao = new TextField();
        ComboBox<Categoria> categoria = Combos.obrigatoria(cat.categoriasArtigo(),
                cat.categoriasArtigo().isEmpty() ? "Crie categorias em Configurações" : "Escolher…");
        ComboBox<Localizacao> localizacao = Combos.opcional(cat.localizacoes(), "(nenhuma)");
        ComboBox<Fornecedor> fornecedor = Combos.opcional(cat.fornecedores(), "(nenhum)");
        ComboBox<String> unidade = Combos.obrigatoria(Unidades.LISTA, "Escolher…");
        unidade.setValue(Unidades.PADRAO);
        TextField stockMinimo = new TextField("0");
        Label erro = new Label();
        erro.setStyle("-fx-text-fill: #b00020;");
        erro.setWrapText(true);

        if (existente != null) {
            codigo.setText(existente.codigo());
            descricao.setText(existente.descricao());
            Combos.selecionar(categoria, Categoria::id, existente.categoriaId());
            Combos.selecionar(localizacao, Localizacao::id, existente.localizacaoId());
            Combos.selecionar(fornecedor, Fornecedor::id, existente.fornecedorId());
            unidade.setValue(existente.unidade());
            stockMinimo.setText(Formatos.numero(existente.stockMinimo()));
        }

        // Imagem: só se grava se o utilizador a alterou (escolher ou remover); senão fica a que já existe.
        CampoImagem campoImagem = new CampoImagem("Escolher a imagem do artigo", erro);
        if (existente != null) {
            try {
                campoImagem.mostrarInicial(svc.imagemArtigo(existente.id()));
            } catch (SQLException | RuntimeException e) {
                erro.setText("Não foi possível ler a imagem: " + Dialogos.mensagemDeErro(e));
            }
        }

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(15));
        ColumnConstraints rotulos = new ColumnConstraints();
        ColumnConstraints campos = new ColumnConstraints(320);
        campos.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(rotulos, campos);
        int r = 0;
        g.addRow(r++, new Label("Código *"), codigo);
        g.addRow(r++, new Label("Descrição *"), descricao);
        g.addRow(r++, new Label("Categoria *"), categoria);
        g.addRow(r++, new Label("Localização"), localizacao);
        g.addRow(r++, new Label("Fornecedor"), fornecedor);
        g.addRow(r++, new Label("Unidade *"), unidade);
        g.addRow(r++, new Label("Stock mínimo"), stockMinimo);
        Label rotuloImagem = new Label("Imagem");
        GridPane.setValignment(rotuloImagem, VPos.TOP);
        g.addRow(r++, rotuloImagem, campoImagem.no());
        g.add(erro, 0, r, 2, 1);
        dlg.getDialogPane().setContent(g);

        boolean[] gravado = {false};
        Button ok = (Button) dlg.getDialogPane().lookupButton(guardar);
        ok.addEventFilter(ActionEvent.ACTION, ev -> {
            try {
                Categoria cSel = categoria.getValue();
                if (cSel == null) {
                    throw new ValidacaoException("A categoria é obrigatória.");
                }
                Localizacao lSel = localizacao.getValue();
                Fornecedor fSel = fornecedor.getValue();
                svc.guardarArtigo(new Artigo(
                        existente == null ? null : existente.id(),
                        codigo.getText(),
                        descricao.getText(),
                        cSel.id(),
                        lSel == null ? null : lSel.id(),
                        fSel == null ? null : fSel.id(),
                        unidade.getValue(),
                        Formatos.lerNumero(stockMinimo.getText(), "O stock mínimo")),
                        campoImagem.alterada(), campoImagem.imagem());
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
