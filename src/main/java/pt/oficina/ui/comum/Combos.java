package pt.oficina.ui.comum;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;

/** ComboBoxes para valores escolhidos em lista (nunca texto livre). */
public final class Combos {

    private Combos() {
    }

    /** Escolha obrigatória: sem valor mostra {@code prompt}. */
    public static <T> ComboBox<T> obrigatoria(List<T> itens, String prompt) {
        ComboBox<T> cb = new ComboBox<>(FXCollections.observableArrayList(itens));
        cb.setPromptText(prompt);
        cb.setMaxWidth(Double.MAX_VALUE);
        return cb;
    }

    /** Escolha opcional: a primeira entrada é "vazio" (null) e mostra {@code rotuloVazio}. */
    public static <T> ComboBox<T> opcional(List<T> itens, String rotuloVazio) {
        List<T> comVazio = new ArrayList<>(itens.size() + 1);
        comVazio.add(null);
        comVazio.addAll(itens);
        return obrigatoria(comVazio, rotuloVazio);
    }

    /** Seleciona o item cujo id é {@code alvo}; sem correspondência não altera a seleção. */
    public static <T> void selecionar(ComboBox<T> cb, Function<T, Long> id, Long alvo) {
        if (alvo == null) {
            return;
        }
        cb.getItems().stream()
                .filter(i -> i != null && Objects.equals(id.apply(i), alvo))
                .findFirst()
                .ifPresent(cb::setValue);
    }
}
