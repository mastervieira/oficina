package pt.oficina.ui.comum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pt.oficina.service.ResultadoImportacao;
import pt.oficina.service.ResultadoImportacao.ErroImportacao;

class TextosImportacaoTest {

    private static ResultadoImportacao resultado(int lidas, int criados, int atualizados, int iguais,
            List<ErroImportacao> erros, Map<String, List<String>> listas, List<String> avisos, List<String> ignoradas) {
        return new ResultadoImportacao(lidas, criados, atualizados, iguais, erros, listas, avisos, ignoradas, false);
    }

    @Test
    void previaMostraContagensListasAvisosEColunasIgnoradas() {
        Map<String, List<String>> listas = new LinkedHashMap<>();
        listas.put("categorias", List.of("Tornos", "Fresas"));
        listas.put("localizações", List.of("Móvel A · Prateleira 1 · Secção C"));
        String texto = TextosImportacao.previa(resultado(10_000, 9_000, 800, 200, List.of(), listas,
                List.of("O stock só se aplica a artigos novos."), List.of("Notas")), "artigos.xlsx");

        assertTrue(texto.startsWith("Ficheiro: artigos.xlsx\n"), texto);
        assertTrue(texto.contains("10" + " " + "000 linhas lidas.") || texto.contains("10 000 linhas lidas."), texto);
        assertTrue(texto.contains("Novos .............. 9") && texto.contains("A atualizar ........ 800")
                && texto.contains("Sem alterações ..... 200"), texto);
        assertTrue(texto.contains("• categorias (2): Tornos, Fresas"), texto);
        assertTrue(texto.contains("• localizações (1): Móvel A · Prateleira 1 · Secção C"), texto);
        assertTrue(texto.contains("Avisos:\n  • O stock só se aplica a artigos novos."), texto);
        assertTrue(texto.contains("Colunas não reconhecidas (ignoradas): Notas"), texto);
        assertFalse(texto.endsWith("\n"));
    }

    @Test
    void previaSemExtrasNaoMostraSeccoesVazias() {
        String texto = TextosImportacao.previa(resultado(1, 1, 0, 0, List.of(), Map.of(), List.of(), List.of()), "m.xlsx");
        assertTrue(texto.contains("1 linha lida."), texto);
        assertFalse(texto.contains("Serão criados") || texto.contains("Avisos") || texto.contains("Colunas"), texto);
    }

    @Test
    void listasLongasSaoAbreviadas() {
        List<String> muitas = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            muitas.add("Loc " + i);
        }
        String texto = TextosImportacao.previa(resultado(20, 20, 0, 0, List.of(), Map.of("localizações", muitas), List.of(), List.of()), "x.xlsx");
        assertTrue(texto.contains("• localizações (20): Loc 1, Loc 2, Loc 3, Loc 4, Loc 5, Loc 6, Loc 7, Loc 8, … (+12)"), texto);
    }

    @Test
    void errosIndicamALinhaDoExcelELimitamOTamanho() {
        List<ErroImportacao> erros = new ArrayList<>();
        erros.add(new ErroImportacao(0, "Falta a coluna obrigatória «Código»."));
        for (int i = 2; i <= 100; i++) {
            erros.add(new ErroImportacao(i, "O código é obrigatório."));
        }
        String texto = TextosImportacao.erros(resultado(99, 0, 0, 0, erros, Map.of(), List.of(), List.of()));
        String[] linhas = texto.split("\n");
        assertEquals("Ficheiro: Falta a coluna obrigatória «Código».", linhas[0]);
        assertEquals("Linha 2: O código é obrigatório.", linhas[1]);
        assertEquals(TextosImportacao.MAX_ERROS + 1, linhas.length);
        assertEquals("… e mais 60 erros.", linhas[linhas.length - 1]); // 100 erros, 40 mostrados
    }

    @Test
    void mensagensFinais() {
        ResultadoImportacao r = resultado(5, 2, 1, 2, List.of(), Map.of(), List.of(), List.of());
        assertEquals("Importação concluída.\n\n  Novos .............. 2\n  Atualizados ........ 1\n  Sem alterações ..... 2",
                TextosImportacao.concluido(r));
        assertEquals("As 5 linhas do ficheiro já estão iguais ao que existe: não há nada para importar.", TextosImportacao.semNada(r));
        assertEquals("A linha do ficheiro já está igual ao que existe: não há nada para importar.",
                TextosImportacao.semNada(resultado(1, 0, 0, 1, List.of(), Map.of(), List.of(), List.of())));
    }

    @Test
    void previaDeRegistosSoMostraAsContagensQueFazemSentido() {
        ResultadoImportacao r = resultado(3, 3, 0, 0, List.of(), Map.of(), List.of("2 linhas coincidem."), List.of());

        String texto = TextosImportacao.previa(r, "mov.xlsx", TextosImportacao.Rotulos.MOVIMENTOS);

        assertTrue(texto.contains("A registar ......... 3"), texto);
        assertFalse(texto.contains("A atualizar"), texto);
        assertFalse(texto.contains("Sem alterações"), texto);
        assertTrue(texto.contains("Avisos:\n  • 2 linhas coincidem."), texto);
        assertTrue(TextosImportacao.concluido(r, TextosImportacao.Rotulos.MOVIMENTOS).contains("Registados ......... 3"));
        assertTrue(TextosImportacao.concluido(r, TextosImportacao.Rotulos.INTERVENCOES).contains("Registadas ......... 3"));
    }

    @Test
    void previaDeEstadosMostraAsMudancasEAsQueJaEstavamNoEstado() {
        ResultadoImportacao r = resultado(10, 4, 0, 6, List.of(), Map.of(), List.of(), List.of());

        String texto = TextosImportacao.previa(r, "estados.xlsx", TextosImportacao.Rotulos.ESTADOS);

        assertTrue(texto.contains("A alterar .......... 4"), texto);
        assertTrue(texto.contains("Já no estado indicado .. 6"), texto);
        assertTrue(TextosImportacao.concluido(r, TextosImportacao.Rotulos.ESTADOS).contains("Alterados .......... 4"));
    }

    @Test
    void semRotulosOsTextosDeMaquinasEArtigosNaoMudam() {
        ResultadoImportacao r = resultado(5, 2, 1, 2, List.of(), Map.of(), List.of(), List.of());

        assertEquals(TextosImportacao.previa(r, "a.xlsx"), TextosImportacao.previa(r, "a.xlsx", null));
        assertEquals(TextosImportacao.concluido(r), TextosImportacao.concluido(r, null));
        assertTrue(TextosImportacao.previa(r, "a.xlsx").contains("  A atualizar ........ 1"));
    }
}
