package pt.oficina.ui.comum;

import javafx.scene.Node;

/** Folha de estilos da aplicação e utilitários para ligar/desligar classes de estilo. */
public final class Estilos {

    /** Caminho da folha de estilos (nos recursos). */
    public static final String CSS = "/pt/oficina/ui/oficina.css";

    private Estilos() {
    }

    /** URL da folha de estilos, para {@code getStylesheets().add(...)}. */
    public static String url() {
        var recurso = Estilos.class.getResource(CSS);
        if (recurso == null) {
            throw new IllegalStateException("Recurso em falta: " + CSS);
        }
        return recurso.toExternalForm();
    }

    /**
     * Liga ou desliga uma classe de estilo num nó, sem a repetir. Serve para linhas de tabela, que são
     * reutilizadas: a classe tem de sair quando a linha passa a mostrar outro item.
     */
    public static void classe(Node no, String classe, boolean ligada) {
        var classes = no.getStyleClass();
        if (ligada) {
            if (!classes.contains(classe)) {
                classes.add(classe);
            }
        } else {
            classes.remove(classe);
        }
    }
}
