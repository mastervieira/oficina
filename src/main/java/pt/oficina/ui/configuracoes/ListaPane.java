package pt.oficina.ui.configuracoes;

import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import pt.oficina.db.Tx;
import pt.oficina.model.enums.TipoCategoria;
import pt.oficina.ui.comum.Combos;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.Formatos;

/**
 * Painel para manter uma lista de apoio (categorias, localizações, fornecedores): adicionar e renomear.
 * Não há remoção: os registos podem estar referenciados por máquinas e artigos.
 * Tem sempre o nome; opcionalmente também um contacto ({@link #comContacto}) ou um tipo ({@link #comTipo}).
 */
final class ListaPane<T> extends VBox {

    /** Valores escritos no formulário; {@code contacto} e {@code tipo} são null se o painel não os tiver. */
    record Campos(String nome, String contacto, TipoCategoria tipo) {
    }

    @FunctionalInterface
    interface Gravador<T> {
        /** {@code atual} é null ao adicionar um registo novo. */
        void gravar(T atual, Campos campos) throws SQLException;
    }

    private final Tx.Work<List<T>> carregar;
    private final Gravador<T> gravador;
    private final ListView<T> lista = new ListView<>();
    private final TextField nome = new TextField();
    private final TextField contacto = new TextField();
    private ComboBox<TipoCategoria> tipo;
    private final Function<T, String> getNome;
    private Function<T, String> getContacto;
    private Function<T, TipoCategoria> getTipo;

    ListaPane(String titulo, Tx.Work<List<T>> carregar, Function<T, String> getNome, Gravador<T> gravador) {
        super(8);
        this.carregar = carregar;
        this.getNome = getNome;
        this.gravador = gravador;
        setPadding(new Insets(10));

        Label t = new Label(titulo);
        t.setStyle("-fx-font-weight: bold;");
        nome.setPromptText("Nome");

        Button adicionar = new Button("Adicionar");
        Button guardar = new Button("Guardar alteração");
        Button limpar = new Button("Limpar");
        guardar.disableProperty().bind(lista.getSelectionModel().selectedItemProperty().isNull());
        adicionar.setOnAction(e -> gravar(null));
        guardar.setOnAction(e -> gravar(lista.getSelectionModel().getSelectedItem()));
        limpar.setOnAction(e -> limparCampos());

        lista.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> {
            if (sel != null) {
                nome.setText(getNome.apply(sel));
                if (getContacto != null) {
                    contacto.setText(Formatos.texto(getContacto.apply(sel)));
                }
                if (tipo != null) {
                    tipo.setValue(getTipo.apply(sel));
                }
            }
        });
        VBox.setVgrow(lista, Priority.ALWAYS);

        getChildren().addAll(t, lista, nome, new HBox(8, adicionar, guardar, limpar));
    }

    /** Acrescenta o campo "Contacto". */
    ListaPane<T> comContacto(Function<T, String> getContacto) {
        this.getContacto = getContacto;
        contacto.setPromptText("Contacto");
        getChildren().add(getChildren().size() - 1, contacto); // antes da linha de botões
        return this;
    }

    /**
     * Acrescenta o campo "Tipo" (escolhido em lista). O tipo escolhe-se ao criar; num registo existente fica
     * visível mas bloqueado.
     */
    ListaPane<T> comTipo(List<TipoCategoria> tipos, Function<T, TipoCategoria> getTipo) {
        this.getTipo = getTipo;
        tipo = Combos.obrigatoria(tipos, "Tipo");
        tipo.setValue(tipos.get(0));
        tipo.disableProperty().bind(lista.getSelectionModel().selectedItemProperty().isNotNull());
        getChildren().add(getChildren().size() - 1, tipo);
        return this;
    }

    void atualizar() {
        Dialogos.tentar(() -> lista.getItems().setAll(carregar.run()));
    }

    private void gravar(T atual) {
        Campos campos = new Campos(nome.getText(),
                getContacto == null ? null : contacto.getText(),
                tipo == null ? null : tipo.getValue());
        if (Dialogos.tentar(() -> gravador.gravar(atual, campos))) {
            limparCampos();
            atualizar();
        }
    }

    private void limparCampos() {
        lista.getSelectionModel().clearSelection();
        nome.clear();
        contacto.clear();
        if (tipo != null && !tipo.getItems().isEmpty()) {
            tipo.setValue(tipo.getItems().get(0));
        }
    }
}
