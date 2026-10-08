package pt.oficina.ui.comum;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import pt.oficina.service.Numeros;

public final class Formatos {

    private static final Locale PT = Locale.of("pt", "PT");

    private Formatos() {
    }

    /** 12 -> "12"; 2.5 -> "2,5". */
    public static String numero(double v) {
        return Numeros.formatar(v); // o texto tem de poder voltar a ser lido por lerNumero
    }

    /** Ver {@link Numeros#ler}: vírgula decimal, sem ponto; vazio devolve 0. */
    public static double lerNumero(String texto, String campo) {
        return Numeros.ler(texto, campo);
    }

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(PT);

    /** Data legível (ex.: "4/out/2026"); null dá "". */
    public static String data(LocalDate d) {
        return d == null ? "" : DATA.format(d);
    }

    public static String texto(String s) {
        return s == null ? "" : s;
    }
}
