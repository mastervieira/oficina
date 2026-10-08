package pt.oficina.service;

import java.text.Normalizer;
import java.util.Locale;

/** Normalização de texto para comparar nomes ("Móvel  A" == "movel a") e cabeçalhos de colunas. */
final class Texto {

    private Texto() {
    }

    /** Sem acentos, em minúsculas, com os espaços reduzidos a um e sem espaços nas pontas. */
    static String chave(String s) {
        if (s == null) {
            return "";
        }
        String semAcentos = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return semAcentos.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Como {@link #chave}, mas só letras e algarismos: "Nº série", "N.º Série" e "nserie" dão todos "nserie". */
    static String chaveCabecalho(String s) {
        return chave(s).replaceAll("[^a-z0-9]", "");
    }
}
