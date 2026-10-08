package pt.oficina.service;

import java.util.List;
import java.util.Map;

/**
 * O que uma importação fez (ou faria, se for só uma simulação). Com erros, nada foi gravado: é tudo ou nada.
 *
 * @param erros          problemas por linha ({@code linha} 0 = o cabeçalho ou o ficheiro em geral)
 * @param listasCriadas  categorias, localizações e fornecedores que não existiam e foram (ou seriam) criados
 * @param avisos         coisas ignoradas que convém saber, sem impedir a importação
 * @param aplicada       verdadeiro só se foi mesmo gravado (não era simulação e não houve erros)
 */
public record ResultadoImportacao(int linhasLidas, int criados, int atualizados, int semAlteracoes,
        List<ErroImportacao> erros, Map<String, List<String>> listasCriadas, List<String> avisos,
        List<String> colunasIgnoradas, boolean aplicada) {

    public record ErroImportacao(int linha, String mensagem) {
    }

    public boolean temErros() {
        return !erros.isEmpty();
    }

    /** Há algo para gravar (registos novos ou alterados)? */
    public boolean temAlteracoes() {
        return criados > 0 || atualizados > 0;
    }
}
