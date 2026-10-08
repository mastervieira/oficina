package pt.oficina.ui.comum;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import pt.oficina.service.ResultadoImportacao;
import pt.oficina.service.ResultadoImportacao.ErroImportacao;

/** Os textos dos diálogos de importação (sem JavaFX, para se poderem testar). */
public final class TextosImportacao {

    private static final Locale PT = Locale.of("pt", "PT");
    private static final int MAX_NOMES = 8;
    static final int MAX_ERROS = 40;

    /**
     * Os nomes das contagens quando a importação só acrescenta registos (movimentos, intervenções) ou muda estados,
     * em vez de criar e atualizar. Um rótulo null não se mostra.
     */
    public record Rotulos(String novos, String novosFeito, String semAlteracoes) {
        public static final Rotulos MOVIMENTOS = new Rotulos("A registar", "Registados", null);
        public static final Rotulos INTERVENCOES = new Rotulos("A registar", "Registadas", null);
        public static final Rotulos ESTADOS = new Rotulos("A alterar", "Alterados", "Já no estado indicado");
    }

    private TextosImportacao() {
    }

    /** Pré-visualização: o que acontecerá se o utilizador confirmar. */
    public static String previa(ResultadoImportacao r, String ficheiro) {
        return previa(r, ficheiro, null);
    }

    /** @param rotulos null para máquinas e artigos (criar e atualizar) */
    public static String previa(ResultadoImportacao r, String ficheiro, Rotulos rotulos) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ficheiro: ").append(ficheiro).append("\n");
        sb.append(numero(r.linhasLidas())).append(r.linhasLidas() == 1 ? " linha lida.\n\n" : " linhas lidas.\n\n");
        if (rotulos == null) {
            sb.append("  Novos .............. ").append(numero(r.criados())).append("\n");
            sb.append("  A atualizar ........ ").append(numero(r.atualizados())).append("\n");
            sb.append("  Sem alterações ..... ").append(numero(r.semAlteracoes())).append("\n");
        } else {
            contagem(sb, rotulos.novos(), r.criados());
            contagem(sb, rotulos.semAlteracoes(), r.semAlteracoes());
        }
        if (!r.listasCriadas().isEmpty()) {
            sb.append("\nSerão criados (ainda não existem; confirme que não são gralhas):\n");
            for (Map.Entry<String, List<String>> e : r.listasCriadas().entrySet()) {
                sb.append("  • ").append(e.getKey()).append(" (").append(e.getValue().size()).append("): ")
                        .append(nomes(e.getValue())).append("\n");
            }
        }
        if (!r.avisos().isEmpty()) {
            sb.append("\nAvisos:\n");
            r.avisos().forEach(a -> sb.append("  • ").append(a).append("\n"));
        }
        if (!r.colunasIgnoradas().isEmpty()) {
            sb.append("\nColunas não reconhecidas (ignoradas): ").append(String.join(", ", r.colunasIgnoradas())).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** Os erros, com o número da linha do Excel, para o utilizador os corrigir no ficheiro. */
    public static String erros(ResultadoImportacao r) {
        StringBuilder sb = new StringBuilder();
        List<ErroImportacao> erros = r.erros();
        for (int i = 0; i < Math.min(erros.size(), MAX_ERROS); i++) {
            ErroImportacao e = erros.get(i);
            sb.append(e.linha() == 0 ? "Ficheiro" : "Linha " + e.linha()).append(": ").append(e.mensagem()).append("\n");
        }
        if (erros.size() > MAX_ERROS) {
            sb.append("… e mais ").append(numero(erros.size() - MAX_ERROS)).append(" erros.\n");
        }
        return sb.toString().stripTrailing();
    }

    public static String concluido(ResultadoImportacao r) {
        return concluido(r, null);
    }

    /** @param rotulos null para máquinas e artigos (criar e atualizar) */
    public static String concluido(ResultadoImportacao r, Rotulos rotulos) {
        StringBuilder sb = new StringBuilder("Importação concluída.\n\n");
        if (rotulos == null) {
            sb.append("  Novos .............. ").append(numero(r.criados())).append("\n");
            sb.append("  Atualizados ........ ").append(numero(r.atualizados())).append("\n");
            sb.append("  Sem alterações ..... ").append(numero(r.semAlteracoes()));
        } else {
            contagem(sb, rotulos.novosFeito(), r.criados());
            contagem(sb, rotulos.semAlteracoes(), r.semAlteracoes());
        }
        return sb.toString().stripTrailing();
    }

    /** "  Rótulo ............. 12": os rótulos ficam alinhados com os pontos. */
    private static void contagem(StringBuilder sb, String rotulo, int n) {
        if (rotulo != null) {
            sb.append("  ").append(rotulo).append(" ").append(".".repeat(Math.max(2, 19 - rotulo.length()))).append(" ")
                    .append(numero(n)).append("\n");
        }
    }

    public static String semNada(ResultadoImportacao r) {
        return (r.linhasLidas() == 1
                ? "A linha do ficheiro já está igual"
                : "As " + numero(r.linhasLidas()) + " linhas do ficheiro já estão iguais")
                + " ao que existe: não há nada para importar.";
    }

    /** "10 000" (pt-PT). */
    public static String numero(int n) {
        return String.format(PT, "%,d", n);
    }

    private static String nomes(List<String> nomes) {
        String lista = String.join(", ", nomes.subList(0, Math.min(nomes.size(), MAX_NOMES)));
        return nomes.size() > MAX_NOMES ? lista + ", … (+" + (nomes.size() - MAX_NOMES) + ")" : lista;
    }
}
