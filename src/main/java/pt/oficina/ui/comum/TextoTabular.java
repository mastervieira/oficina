package pt.oficina.ui.comum;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Formata linhas de uma tabela como texto: colunas separadas por tabulação e linhas por mudança de linha, que é
 * o formato que uma folha de cálculo (Excel, LibreOffice) reconhece ao colar, e que cola bem em qualquer campo de texto.
 *
 * <p>Ao colar, a folha de cálculo interpreta cada valor como se fosse escrito à mão: um texto que comece por = + - @
 * passaria a ser uma fórmula (por exemplo, uma descrição «=HYPERLINK(...)» vinda de um Excel de um fornecedor). Esses
 * valores levam um apóstrofo à frente, que a folha de cálculo entende como «isto é texto».
 */
public final class TextoTabular {

    private TextoTabular() {
    }

    public static String formatar(List<String> titulos, List<List<String>> linhas, boolean comCabecalhos) {
        List<String> saida = new ArrayList<>();
        if (comCabecalhos) {
            saida.add(linha(titulos));
        }
        for (List<String> l : linhas) {
            saida.add(linha(l));
        }
        return String.join("\n", saida);
    }

    private static String linha(List<String> valores) {
        return valores.stream().map(TextoTabular::limpar).collect(Collectors.joining("\t"));
    }

    /** Um valor com tabulações ou mudanças de linha partiria as colunas e as linhas: troca-se por um espaço. */
    static String limpar(String valor) {
        return valor == null ? "" : semFormula(valor.replaceAll("[\t\r\n]+", " "));
    }

    /** Números como "-5" ou "+2,5" colam-se como números; tudo o resto que comece por = + - @ fica como texto. */
    private static final Pattern NUMERO = Pattern.compile("[-+]?\\d+(,\\d+)?");

    /** Põe um apóstrofo à frente de um valor que a folha de cálculo tomaria por fórmula. */
    public static String semFormula(String valor) {
        if (valor == null || valor.isEmpty() || "=+-@".indexOf(valor.charAt(0)) < 0 || NUMERO.matcher(valor).matches()) {
            return valor;
        }
        return "'" + valor;
    }
}
