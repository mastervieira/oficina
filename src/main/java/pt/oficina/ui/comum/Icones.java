package pt.oficina.ui.comum;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;

/** Botões só com ícone (a forma está em oficina.css), com dica de ferramenta e texto para leitores de ecrã. */
public final class Icones {

    private Icones() {
    }

    public static Button botaoEditar(String dica) {
        return botao("icone-editar", dica, false);
    }

    /** Botão de eliminar: vermelho, com o ícone a branco. */
    public static Button botaoEliminar(String dica) {
        return botao("icone-eliminar", dica, true);
    }

    private static Button botao(String icone, String dica, boolean perigo) {
        Region forma = new Region();
        forma.getStyleClass().addAll("icone", icone);
        Button b = new Button();
        b.setGraphic(forma);
        b.getStyleClass().add("botao-icone");
        if (perigo) {
            b.getStyleClass().add("botao-perigo");
        }
        b.setTooltip(new Tooltip(dica));
        b.setAccessibleText(dica);
        return b;
    }
}
