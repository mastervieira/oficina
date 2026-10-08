package pt.oficina.ui.comum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextoTabularTest {

    private static final List<String> TITULOS = List.of("Código", "Descrição", "Stock");

    @Test
    void colunasPorTabulacaoELinhasPorMudancaDeLinha() {
        List<List<String>> linhas = List.of(List.of("A1", "Broca Ø5", "12"), List.of("B2", "Fresa", "0"));
        assertEquals("A1\tBroca Ø5\t12\nB2\tFresa\t0", TextoTabular.formatar(TITULOS, linhas, false));
        assertEquals("Código\tDescrição\tStock\nA1\tBroca Ø5\t12\nB2\tFresa\t0", TextoTabular.formatar(TITULOS, linhas, true));
    }

    @Test
    void umaLinhaNaoTemMudancaDeLinhaNoFim() {
        // colar num campo de texto não deve trazer uma linha em branco atrás
        assertEquals("A1\tBroca\t12", TextoTabular.formatar(TITULOS, List.of(List.of("A1", "Broca", "12")), false));
    }

    @Test
    void valoresComTabulacaoOuMudancaDeLinhaNaoPartemAsColunas() {
        List<List<String>> linhas = List.of(List.of("A1", "com\ttab", "linha1\nlinha2"), List.of("B2", "crlf\r\nx", "ok"));
        assertEquals("A1\tcom tab\tlinha1 linha2\nB2\tcrlf x\tok", TextoTabular.formatar(TITULOS, linhas, false));
    }

    @Test
    void celulasVaziasEnulosMantemAPosicaoDasColunas() {
        assertEquals("A1\t\t\n\t\tz", TextoTabular.formatar(TITULOS,
                List.of(Arrays.asList("A1", "", null), Arrays.asList(null, "", "z")), false));
    }

    @Test
    void semLinhas() {
        assertEquals("", TextoTabular.formatar(TITULOS, List.of(), false));
        assertEquals("Código\tDescrição\tStock", TextoTabular.formatar(TITULOS, List.of(), true));
    }

    @Test
    void folhaDeEstilosExisteETemAsClassesUsadasNoCodigo() throws Exception {
        String css;
        try (var in = Estilos.class.getResourceAsStream(Estilos.CSS)) {
            assertTrue(in != null, "falta " + Estilos.CSS);
            css = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String classe : new String[] {".botao-perigo", ".linha-alerta", ".linha-inoperativo", ".linha-manutencao",
            ".linha-abatido", ".aviso-copia"}) {
            assertTrue(css.contains(classe), "falta a classe " + classe);
        }
        assertTrue(Estilos.url().endsWith("oficina.css"));
        // Armadilha do tema: -fx-selection-bar-text é definido como -fx-text-background-color; usá-lo como valor
        // dessa propriedade cria uma referência circular (e o JavaFX despeja milhares de linhas de aviso).
        assertTrue(!css.matches("(?s).*-fx-text-background-color\\s*:\\s*-fx-selection-bar-text.*"),
                "referência circular no CSS");
    }

    @Test
    void valoresQueUmaFolhaDeCalculoTomariaPorFormulaFicamComoTexto() {
        List<List<String>> linhas = List.of(List.of("=HYPERLINK(\"http://x\";\"clique\")", "+1+2", "@SOMA(A1)"),
                List.of("-2+3", "-5", "+2,5"), List.of("a=b", "", "12"));
        assertEquals("'=HYPERLINK(\"http://x\";\"clique\")\t'+1+2\t'@SOMA(A1)\n'-2+3\t-5\t+2,5\na=b\t\t12",
                TextoTabular.formatar(TITULOS, linhas, false));
        assertEquals("'=1", TextoTabular.semFormula("=1"));
        assertEquals("Broca", TextoTabular.semFormula("Broca"));
    }
}
