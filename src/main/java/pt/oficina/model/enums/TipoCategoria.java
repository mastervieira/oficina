package pt.oficina.model.enums;

import java.util.Arrays;
import java.util.List;

/** MAQUINA é o tipo das categorias de máquinas; todos os outros são tipos de categorias de artigos. */
public enum TipoCategoria {
    MAQUINA("Máquina"),
    ARTIGO("Artigo"),
    FERRAMENTA("Ferramenta"),
    CONSUMIVEL("Consumível"),
    OUTROS("Outros");

    private final String etiqueta;

    TipoCategoria(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    /** Verdadeiro para os tipos de categorias de artigos (todos menos MAQUINA). */
    public boolean eDeArtigos() {
        return this != MAQUINA;
    }

    /** Tipos que se podem escolher ao criar uma categoria de artigos. */
    public static List<TipoCategoria> deArtigos() {
        return Arrays.stream(values()).filter(TipoCategoria::eDeArtigos).toList();
    }

    @Override
    public String toString() {
        return etiqueta;
    }
}
