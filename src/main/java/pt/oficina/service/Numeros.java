package pt.oficina.service;

import java.text.NumberFormat;
import java.util.Locale;

/** Leitura de números escritos por pessoas (formulários, células de texto de uma folha de cálculo). */
public final class Numeros {

    /** Casas decimais aceites na entrada (e mostradas na saída: têm de coincidir para o texto voltar a ler-se). */
    public static final int MAX_DECIMAIS = 6;

    private static final Locale PT = Locale.of("pt", "PT");

    private Numeros() {
    }

    /** 12 -> "12"; 2.5 -> "2,5"; sem separador de milhares, para o texto voltar a ler-se com {@link #ler}. */
    public static String formatar(double v) {
        NumberFormat nf = NumberFormat.getNumberInstance(PT);
        nf.setMaximumFractionDigits(MAX_DECIMAIS);
        nf.setGroupingUsed(false);
        return nf.format(v);
    }

    /**
     * Lê um número não negativo escrito com vírgula decimal ("2,5"); vazio devolve 0. O ponto é recusado: em
     * pt-PT "1.000" é mil, e lê-lo como 1 alteraria o stock em silêncio.
     */
    public static double ler(String texto, String campo) {
        if (texto == null || texto.isBlank()) {
            return 0;
        }
        String t = texto.trim();
        if (t.contains(".")) {
            throw new ValidacaoException(campo + ": use a vírgula como separador decimal (ex.: 2,5).");
        }
        if (!t.matches("\\d{1,10}(,\\d{1," + MAX_DECIMAIS + "})?")) {
            throw new ValidacaoException(campo + " não é um número válido.");
        }
        double v = Double.parseDouble(t.replace(',', '.'));
        if (v > Limites.MAX_VALOR) {
            throw new ValidacaoException(campo + " é demasiado grande (máximo 1 000 000 000).");
        }
        return v;
    }
}
