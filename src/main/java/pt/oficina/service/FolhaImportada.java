package pt.oficina.service;

import java.util.List;

/**
 * O conteúdo de uma folha de cálculo, já lido do ficheiro: o serviço de importação não sabe de Excel.
 * Cada valor é String, Double, LocalDate, Boolean ou null (célula vazia), alinhado com {@code cabecalhos}.
 */
public record FolhaImportada(List<String> cabecalhos, List<LinhaImportada> linhas) {

    /**
     * O valor de uma célula com uma fórmula que nunca foi calculada (o ficheiro foi gerado por um programa e nunca
     * aberto no Excel): não há valor para ler. Quem a usar deve recusá-la em vez de assumir 0.
     */
    public static final Object FORMULA_SEM_VALOR = new Object() {
        @Override
        public String toString() {
            return "(fórmula sem valor calculado)";
        }
    };

    /** {@code numero} é a linha no ficheiro (a primeira é 1), para as mensagens de erro. */
    public record LinhaImportada(int numero, List<Object> valores) {
    }
}
