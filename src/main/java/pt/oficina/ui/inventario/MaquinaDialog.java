package pt.oficina.ui.inventario;

import java.sql.SQLException;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
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
import pt.oficina.model.Categoria;
import pt.oficina.model.Fornecedor;
import pt.oficina.model.Localizacao;
import pt.oficina.model.Maquina;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.service.Catalogos;
import pt.oficina.service.InventarioService;
import pt.oficina.service.ValidacaoException;
import pt.oficina.ui.comum.CampoData;
import pt.oficina.ui.comum.CampoImagem;
import pt.oficina.ui.comum.Combos;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.Formatos;

/**
 * Formulário de registo/edição de máquina. Grava ao carregar em "Guardar"; os erros ficam no próprio formulário.
 * A imagem (foto) da máquina é o último campo.
 */
final class MaquinaDialog {

    private MaquinaDialog() {
    }

    /** @return true se a máquina foi gravada */
    static boolean mostrar(Window dono, InventarioService svc, Catalogos cat, Maquina existente) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initOwner(dono);
        dlg.setTitle(existente == null ? "Nova máquina" : "Editar máquina");
        ButtonType guardar = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(guardar, ButtonType.CANCEL);

        TextField codigo = new TextField();
        TextField descricao = new TextField();
        TextField nSerie = new TextField();
        ComboBox<Categoria> categoria = Combos.obrigatoria(cat.categoriasMaquina(),
                cat.categoriasMaquina().isEmpty() ? "Crie categorias em Configurações" : "Escolher…");
        ComboBox<Localizacao> localizacao = Combos.opcional(cat.localizacoes(), "(nenhuma)");
        ComboBox<Fornecedor> fornecedor = Combos.opcional(cat.fornecedores(), "(nenhum)");
        DatePicker aquisicao = CampoData.criar(null);
        Label estado = new Label(existente == null ? EstadoMaquina.ATIVO.toString() : existente.estado().toString());
        Label erro = new Label();
        erro.setStyle("-fx-text-fill: #b00020;");
        erro.setWrapText(true);

        if (existente != null) {
            codigo.setText(existente.codigo());
            descricao.setText(existente.descricao());
            nSerie.setText(Formatos.texto(existente.nSerie()));
            Combos.selecionar(categoria, Categoria::id, existente.categoriaId());
            Combos.selecionar(localizacao, Localizacao::id, existente.localizacaoId());
            Combos.selecionar(fornecedor, Fornecedor::id, existente.fornecedorId());
            aquisicao.setValue(existente.dataAquisicao());
        }

        // Imagem: só se grava se o utilizador a alterou (escolher ou remover); senão fica a que já existe.
        CampoImagem campoImagem = new CampoImagem("Escolher a imagem da máquina", erro);
        if (existente != null) {
            try {
                campoImagem.mostrarInicial(svc.imagemMaquina(existente.id()));
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
        g.addRow(r++, new Label("Nº de série"), nSerie);
        g.addRow(r++, new Label("Categoria *"), categoria);
        g.addRow(r++, new Label("Localização"), localizacao);
        g.addRow(r++, new Label("Fornecedor"), fornecedor);
        g.addRow(r++, new Label("Data de aquisição"), aquisicao);
        g.addRow(r++, new Label("Estado"), estado);
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
                svc.guardarMaquina(new Maquina(
                        existente == null ? null : existente.id(),
                        codigo.getText(),
                        descricao.getText(),
                        nSerie.getText(),
                        cSel.id(),
                        lSel == null ? null : lSel.id(),
                        fSel == null ? null : fSel.id(),
                        CampoData.ler(aquisicao, "A data de aquisição"),
                        existente == null ? EstadoMaquina.ATIVO : existente.estado()),
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
