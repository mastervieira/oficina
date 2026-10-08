package pt.oficina.model;

import java.util.List;

/** Unidades de medida permitidas nos artigos (escolhidas em lista, nunca texto livre). */
public final class Unidades {

    public static final String PADRAO = "un";
    public static final List<String> LISTA = List.of("un", "par", "cx", "rolo", "m", "kg", "g", "l");

    private Unidades() {
    }
}
