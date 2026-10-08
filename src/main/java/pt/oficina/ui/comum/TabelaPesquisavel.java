package pt.oficina.ui.comum;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Tabela com caixa de pesquisa. A pesquisa ignora maiúsculas e acentos e exige todas as palavras escritas.
 * Filtra em memória; o texto de pesquisa de cada linha normaliza-se uma só vez (milhares de registos, a cada tecla).
 *
 * <p>Copiar: selecione uma ou várias linhas (Ctrl+clique, Shift+clique, Ctrl+A) e use Ctrl+C (valores separados
 * por tabulação, prontos a colar numa folha de cálculo) ou Ctrl+Shift+C (com a linha de cabeçalhos). O botão direito
 * numa célula oferece ainda "Copiar célula".
 */
public class TabelaPesquisavel<T> extends VBox {

    private static final KeyCombination COPIAR = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination COPIAR_COM_CABECALHOS =
            new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

    /** Uma coluna de texto: o que se copia é o mesmo texto que se mostra. */
    private record ColunaTexto<T>(String titulo, Function<T, String> valor) {
    }

    private final TableView<T> tabela = new TableView<>();
    private final ObservableList<T> itens = FXCollections.observableArrayList();
    private final FilteredList<T> filtrados = new FilteredList<>(itens);
    private final TextField pesquisa = new TextField();
    private final Label contagem = new Label();
    private final Label aviso = new Label();
    private final PauseTransition esconderAviso = new PauseTransition(Duration.seconds(2.5));
    private final HBox barra = new HBox(10);
    private final Function<T, String> textoPesquisa;
    private final List<ColunaTexto<T>> colunasTexto = new ArrayList<>();
    private Predicate<T> filtroExtra = t -> true;
    /** Texto de pesquisa já normalizado de cada linha; refaz-se em {@link #setItens} (os nomes podem ter mudado). */
    private final Map<T, String> textoNormalizado = new IdentityHashMap<>();

    public TabelaPesquisavel(Function<T, String> textoPesquisa) {
        super(8);
        this.textoPesquisa = textoPesquisa;
        setPadding(new Insets(10));

        pesquisa.setPromptText("Pesquisar…");
        pesquisa.setPrefWidth(280);
        pesquisa.textProperty().addListener((o, a, b) -> atualizarFiltro());
        contagem.setMinWidth(90);
        aviso.getStyleClass().add("aviso-copia");
        esconderAviso.setOnFinished(e -> mostrarAviso(null));
        mostrarAviso(null);
        barra.setAlignment(Pos.CENTER_LEFT);
        barra.getChildren().addAll(pesquisa, contagem, aviso);

        SortedList<T> ordenados = new SortedList<>(filtrados);
        ordenados.comparatorProperty().bind(tabela.comparatorProperty());
        tabela.setItems(ordenados);
        tabela.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tabela.setPlaceholder(new Label("Sem registos."));
        tabela.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tabela.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (COPIAR_COM_CABECALHOS.match(e)) {
                copiarSelecao(true);
                e.consume();
            } else if (COPIAR.match(e)) {
                copiarSelecao(false);
                e.consume();
            }
        });
        VBox.setVgrow(tabela, Priority.ALWAYS);

        getChildren().addAll(barra, tabela);
    }

    /** Barra por cima da tabela, para juntar filtros e botões. */
    public HBox barra() {
        return barra;
    }

    public TableView<T> tabela() {
        return tabela;
    }

    /** A linha selecionada (se houver várias, a última a ser selecionada). */
    public T selecionado() {
        return tabela.getSelectionModel().getSelectedItem();
    }

    public void addColuna(String titulo, Function<T, String> valor, double largura) {
        TableColumn<T, String> col = new TableColumn<>(titulo);
        col.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
        col.setCellFactory(c -> criarCelula());
        col.setPrefWidth(largura);
        tabela.getColumns().add(col);
        colunasTexto.add(new ColunaTexto<>(titulo, valor));
    }

    /**
     * Coluna de largura fixa: não encolhe quando a janela é estreita. Para valores curtos (código, estado, datas,
     * quantidades) que ficariam cortados ("Inoperati…"); as colunas de texto livre é que absorvem a falta de espaço.
     */
    public void addColunaFixa(String titulo, Function<T, String> valor, double largura) {
        addColuna(titulo, valor, largura);
        TableColumn<T, ?> col = tabela.getColumns().get(tabela.getColumns().size() - 1);
        col.setMinWidth(largura);
        col.setMaxWidth(largura);
    }

    /**
     * Coluna "Ações" com dois botões de ícone por linha: editar (lápis) e eliminar (caixote, a vermelho). Ao
     * clicar, chama a ação com o item dessa linha. Largura fixa.
     */
    public void addColunaEditarEliminar(Consumer<T> editar, Consumer<T> eliminar) {
        TableColumn<T, Void> col = new TableColumn<>("Ações");
        col.setSortable(false);   // não faz sentido ordenar por botões
        col.setMinWidth(90);      // largura fixa: a política FLEX_LAST_COLUMN
        col.setMaxWidth(90);      // não deve esticá-la
        col.setCellFactory(c -> new TableCell<T, Void>() {
            private final Button botaoEditar = Icones.botaoEditar("Editar");
            private final Button botaoEliminar = Icones.botaoEliminar("Eliminar");
            private final HBox caixa = new HBox(6, botaoEditar, botaoEliminar);

            {   // bloco de inicialização: corre uma vez por célula
                caixa.setAlignment(Pos.CENTER);
                botaoEditar.setOnAction(e -> executar(editar));
                botaoEliminar.setOnAction(e -> executar(eliminar));
            }

            private void executar(Consumer<T> acao) {
                T linha = getTableRow().getItem(); // as células são reutilizadas: pergunta-se a linha ao clicar
                if (linha != null) {
                    acao.accept(linha);
                }
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : caixa);
            }
        });
        tabela.getColumns().add(col);
    }

    // ---- copiar ----------------------------------------------------------

    /** Célula de texto com o menu de contexto de copiar. */
    private TableCell<T, String> criarCelula() {
        TableCell<T, String> celula = new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        };
        celula.setOnContextMenuRequested(e -> {
            if (celula.getTableRow() != null && celula.getTableRow().getItem() != null) {
                criarMenu(celula).show(celula, e.getScreenX(), e.getScreenY());
                e.consume();
            }
        });
        return celula;
    }

    /**
     * Menu de contexto de uma célula. Se a linha clicada faz parte da seleção, copiam-se todas as linhas
     * selecionadas; senão, só essa linha.
     */
    protected ContextMenu criarMenu(TableCell<T, String> celula) {
        T clicada = celula.getTableRow().getItem();
        List<T> selecionadas = linhasSelecionadas();
        List<T> linhas = selecionadas.contains(clicada) ? selecionadas : List.of(clicada);
        String quantas = linhas.size() == 1 ? "linha" : linhas.size() + " linhas";

        MenuItem soCelula = new MenuItem("Copiar célula");
        soCelula.setOnAction(e -> copiarTexto(celula.getItem() == null ? "" : TextoTabular.semFormula(celula.getItem()),
                "Célula copiada"));
        MenuItem valores = new MenuItem("Copiar " + quantas + " (Ctrl+C)");
        valores.setOnAction(e -> copiarLinhas(linhas, false));
        MenuItem comCabecalhos = new MenuItem("Copiar " + quantas + " com cabeçalhos (Ctrl+Shift+C)");
        comCabecalhos.setOnAction(e -> copiarLinhas(linhas, true));
        return new ContextMenu(soCelula, valores, comCabecalhos);
    }

    /** As linhas selecionadas, pela ordem em que aparecem na tabela. */
    private List<T> linhasSelecionadas() {
        return tabela.getSelectionModel().getSelectedIndices().stream()
                .sorted()
                .map(i -> tabela.getItems().get(i))
                .toList();
    }

    private void copiarSelecao(boolean comCabecalhos) {
        copiarLinhas(linhasSelecionadas(), comCabecalhos);
    }

    private void copiarLinhas(List<T> linhas, boolean comCabecalhos) {
        if (linhas.isEmpty()) {
            return;
        }
        copiarTexto(textoDasLinhas(linhas, comCabecalhos),
                linhas.size() == 1 ? "1 linha copiada" : linhas.size() + " linhas copiadas");
    }

    /** O texto das colunas de dados (sem a coluna de ações), tal como é mostrado. */
    public String textoDasLinhas(List<T> linhas, boolean comCabecalhos) {
        List<String> titulos = colunasTexto.stream().map(ColunaTexto::titulo).toList();
        List<List<String>> valores = linhas.stream()
                .map(l -> colunasTexto.stream().map(c -> c.valor().apply(l)).toList())
                .toList();
        return TextoTabular.formatar(titulos, valores, comCabecalhos);
    }

    private void copiarTexto(String texto, String confirmacao) {
        copiar(texto);
        mostrarAviso(confirmacao);
        esconderAviso.playFromStart();
    }

    /** Entrega o texto à área de transferência do sistema. Separado para os testes o poderem substituir. */
    protected void copiar(String texto) {
        ClipboardContent conteudo = new ClipboardContent();
        conteudo.putString(texto);
        Clipboard.getSystemClipboard().setContent(conteudo);
    }

    private void mostrarAviso(String texto) {
        boolean visivel = texto != null;
        aviso.setText(visivel ? "✓ " + texto : "");
        aviso.setVisible(visivel);
        aviso.setManaged(visivel); // sem texto não ocupa espaço na barra
    }

    // ---- dados e filtro --------------------------------------------------

    /** As linhas que se veem (depois da pesquisa e do filtro), pela ordem em que aparecem. */
    public List<T> itensVisiveis() {
        return List.copyOf(tabela.getItems());
    }

    /** O total de linhas, sem pesquisa nem filtro. */
    public int totalItens() {
        return itens.size();
    }

    public void setItens(List<T> novos) {
        textoNormalizado.clear();
        itens.setAll(novos);
        atualizarContagem();
    }

    public void setFiltroExtra(Predicate<T> filtro) {
        this.filtroExtra = filtro;
        atualizarFiltro();
    }

    public void setAoDuploClique(Consumer<T> acao) {
        tabela.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && selecionado() != null) {
                acao.accept(selecionado());
            }
        });
    }

    private void atualizarFiltro() {
        String[] palavras = normalizar(pesquisa.getText()).split("\\s+");
        filtrados.setPredicate(t -> {
            if (!filtroExtra.test(t)) {
                return false;
            }
            String texto = textoNormalizado.computeIfAbsent(t, i -> normalizar(textoPesquisa.apply(i)));
            for (String p : palavras) {
                if (!p.isEmpty() && !texto.contains(p)) {
                    return false;
                }
            }
            return true;
        });
        atualizarContagem();
    }

    private void atualizarContagem() {
        contagem.setText(filtrados.size() + " de " + itens.size());
    }

    private static String normalizar(String s) {
        if (s == null) {
            return "";
        }
        String semAcentos = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return semAcentos.toLowerCase(Locale.ROOT).trim();
    }
}
