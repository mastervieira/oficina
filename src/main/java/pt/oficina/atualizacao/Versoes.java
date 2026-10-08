package pt.oficina.atualizacao;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Comparação de versões no formato MAJOR.MINOR.PATCH com sufixo opcional (-SNAPSHOT, -rc1, ...). */
final class Versoes {

    private static final Pattern FORMATO = Pattern.compile("(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})(?:-([A-Za-z0-9.]{1,32}))?");

    private Versoes() {
    }

    static boolean valida(String versao) {
        return versao != null && FORMATO.matcher(versao).matches();
    }

    /**
     * Negativo se {@code a} é mais antiga que {@code b}. Uma versão com sufixo ("1.2.0-SNAPSHOT") é anterior à final
     * com os mesmos números ("1.2.0").
     *
     * @throws IllegalArgumentException se alguma não tem o formato esperado
     */
    static int comparar(String a, String b) {
        Matcher ma = FORMATO.matcher(a);
        Matcher mb = FORMATO.matcher(b);
        if (!ma.matches() || !mb.matches()) {
            throw new IllegalArgumentException("Versão inválida: " + a + " / " + b);
        }
        for (int i = 1; i <= 3; i++) {
            int c = Integer.compare(Integer.parseInt(ma.group(i)), Integer.parseInt(mb.group(i)));
            if (c != 0) {
                return c;
            }
        }
        String sa = ma.group(4);
        String sb = mb.group(4);
        if (sa == null || sb == null) {
            return sa == null ? (sb == null ? 0 : 1) : -1;
        }
        return sa.compareTo(sb);
    }
}
