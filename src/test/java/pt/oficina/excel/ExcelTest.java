package pt.oficina.excel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pt.oficina.service.FolhaImportada;

class ExcelTest {

    @TempDir
    Path tmp;

    private Path ficheiro(String nome) {
        return tmp.resolve(nome);
    }

    private static List<Object> linha(Object... v) {
        return Arrays.asList(v);
    }

    private void gravar(Path destino, XSSFWorkbook livro) throws IOException {
        try (livro; OutputStream out = Files.newOutputStream(destino)) {
            livro.write(out);
        }
    }

    // ---- escrever e ler ----------------------------------------------------

    @Test
    void escreveELeComOsTiposCertos() throws IOException {
        Path f = ficheiro("tipos.xlsx");
        ExcelEscritor.escrever(f, "Dados", List.of("Texto", "Número", "Data", "Booleano", "Vazio"), List.of(
                linha("a", 12.5, LocalDate.of(2026, 10, 5), true, null),
                linha("=1+1", 3, LocalDate.of(1999, 12, 31), false, "fim")));

        FolhaImportada folha = ExcelLeitor.ler(f);
        assertEquals(List.of("Texto", "Número", "Data", "Booleano", "Vazio"), folha.cabecalhos());
        assertEquals(2, folha.linhas().size());
        assertEquals(2, folha.linhas().get(0).numero());
        assertEquals(3, folha.linhas().get(1).numero());
        assertEquals(linha("a", 12.5, LocalDate.of(2026, 10, 5), true), folha.linhas().get(0).valores().subList(0, 4));
        // um texto que parece fórmula fica como texto: nunca é interpretado
        assertEquals(linha("=1+1", 3.0, LocalDate.of(1999, 12, 31), false, "fim"), folha.linhas().get(1).valores());
    }

    @Test
    void cabecalhoDepoisDeLinhasEmBrancoELinhasVaziasSaltadas() throws IOException {
        Path f = ficheiro("brancos.xlsx");
        XSSFWorkbook livro = new XSSFWorkbook();
        Sheet s = livro.createSheet("Folha");
        s.createRow(0).createCell(0).setCellValue("   "); // só espaços: conta como vazia
        s.createRow(1);
        Row h = s.createRow(3);
        h.createCell(0).setCellValue("Código");
        h.createCell(1).setCellValue("Descrição");
        Row d1 = s.createRow(4);
        d1.createCell(0).setCellValue("A1");
        d1.createCell(1).setCellValue("Alfa");
        s.createRow(5).createCell(0).setCellValue(""); // linha vazia no meio
        Row d2 = s.createRow(6);
        d2.createCell(0).setCellValue("B2");
        d2.createCell(5).setCellValue("para além das colunas do cabeçalho"); // o serviço decide o que fazer com ela
        gravar(f, livro);

        FolhaImportada folha = ExcelLeitor.ler(f);
        assertEquals(List.of("Código", "Descrição"), folha.cabecalhos());
        assertEquals(List.of(5, 7), folha.linhas().stream().map(l -> l.numero()).toList(), "números = linhas no Excel (1-based)");
        assertEquals(linha("B2"), folha.linhas().get(1).valores().subList(0, 1));
        assertEquals("para além das colunas do cabeçalho", folha.linhas().get(1).valores().get(5), "as linhas não se cortam pela largura do cabeçalho");
    }

    @Test
    void formulasDaoOUltimoValorCalculadoEDatasSaoDatas() throws IOException {
        Path f = ficheiro("formulas.xlsx");
        XSSFWorkbook livro = new XSSFWorkbook();
        Sheet s = livro.createSheet("F");
        s.createRow(0).createCell(0).setCellValue("Valor");
        s.getRow(0).createCell(1).setCellValue("Data");
        s.getRow(0).createCell(2).setCellValue("Número simples");
        s.getRow(0).createCell(3).setCellValue("Texto-data");
        Row r = s.createRow(1);
        r.createCell(0).setCellFormula("1+2");
        r.createCell(1).setCellValue(LocalDate.of(2026, 1, 31));
        CellStyle data = livro.createCellStyle();
        data.setDataFormat(livro.createDataFormat().getFormat("dd/mm/yyyy"));
        r.getCell(1).setCellStyle(data);
        r.createCell(2).setCellValue(45000); // sem formato de data: é um número
        r.createCell(3).setCellValue("31/12/2025"); // texto: fica texto (quem usa decide)
        livro.getCreationHelper().createFormulaEvaluator().evaluateAll();
        gravar(f, livro);

        List<Object> v = ExcelLeitor.ler(f).linhas().get(0).valores();
        assertEquals(3.0, v.get(0));
        assertEquals(LocalDate.of(2026, 1, 31), v.get(1));
        assertEquals(45000.0, v.get(2));
        assertEquals("31/12/2025", v.get(3));
    }

    @Test
    void formulaSemValorCalculadoNaoViraZeroEmSilencio() throws IOException {
        Path f = ficheiro("sem-valor.xlsx");
        XSSFWorkbook livro = new XSSFWorkbook();
        Sheet s = livro.createSheet("F");
        s.createRow(0).createCell(0).setCellValue("Valor");
        s.createRow(1).createCell(0).setCellFormula("1+2"); // nunca avaliada: não há valor em cache
        gravar(f, livro);
        assertEquals(FolhaImportada.FORMULA_SEM_VALOR, ExcelLeitor.ler(f).linhas().get(0).valores().get(0));
    }

    @Test
    void formulaComValorVazioEscritoPorOutrosProgramasTambemENaoCalculada() throws IOException {
        // alguns programas (ex.: bibliotecas Python) escrevem <f>...</f><v></v>: continua a não haver valor
        Path f = ficheiro("v-vazio.xlsx");
        XSSFWorkbook livro = new XSSFWorkbook();
        Sheet s = livro.createSheet("F");
        s.createRow(0).createCell(0).setCellValue("Valor");
        s.getRow(0).createCell(1).setCellValue("Texto vazio");
        Row r = s.createRow(1);
        r.createCell(0).setCellFormula("1+2");
        ((org.apache.poi.xssf.usermodel.XSSFCell) r.getCell(0)).getCTCell().setV("");
        r.createCell(1).setCellFormula("\"\"");   // uma fórmula que devolve texto vazio: é um resultado legítimo
        ((org.apache.poi.xssf.usermodel.XSSFCell) r.getCell(1)).getCTCell().setT(org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType.STR);
        ((org.apache.poi.xssf.usermodel.XSSFCell) r.getCell(1)).getCTCell().setV("");
        gravar(f, livro);
        List<Object> v = ExcelLeitor.ler(f).linhas().get(0).valores();
        assertEquals(FolhaImportada.FORMULA_SEM_VALOR, v.get(0));
        assertTrue("".equals(v.get(1)) || v.get(1) == null, "texto vazio legítimo: " + v.get(1));
    }

    @Test
    void soAPrimeiraFolhaELida() throws IOException {
        Path f = ficheiro("duas.xlsx");
        XSSFWorkbook livro = new XSSFWorkbook();
        livro.createSheet("A").createRow(0).createCell(0).setCellValue("Código");
        livro.getSheetAt(0).createRow(1).createCell(0).setCellValue("primeira");
        livro.createSheet("B").createRow(0).createCell(0).setCellValue("outra coisa");
        gravar(f, livro);
        FolhaImportada folha = ExcelLeitor.ler(f);
        assertEquals(List.of("Código"), folha.cabecalhos());
        assertEquals("primeira", folha.linhas().get(0).valores().get(0));
    }

    @Test
    void ficheiroSemConteudoDaFolhaVazia() throws IOException {
        Path f = ficheiro("vazio.xlsx");
        XSSFWorkbook livro = new XSSFWorkbook();
        livro.createSheet("Nada");
        gravar(f, livro);
        FolhaImportada folha = ExcelLeitor.ler(f);
        assertEquals(List.of(), folha.cabecalhos());
        assertEquals(List.of(), folha.linhas());
    }

    // ---- ficheiros inválidos -----------------------------------------------

    @Test
    void recusaFormatosQueNaoSaoXlsxComUmaMensagemUtil() throws IOException {
        for (String nome : new String[] {"dados.xls", "dados.csv", "dados.txt", "dados"}) {
            Path f = Files.writeString(ficheiro(nome), "a;b");
            IOException e = assertThrows(IOException.class, () -> ExcelLeitor.ler(f), nome);
            assertTrue(e.getMessage().contains(".xlsx"), e.getMessage());
        }
    }

    @Test
    void ficheiroCorrompidoOuInexistenteDaUmaMensagemEmVezDeUmaExcecaoCrua() throws IOException {
        Path lixo = Files.writeString(ficheiro("lixo.xlsx"), "isto não é um zip");
        IOException e1 = assertThrows(IOException.class, () -> ExcelLeitor.ler(lixo));
        assertTrue(e1.getMessage().contains("Não foi possível ler o ficheiro Excel"), e1.getMessage());
        Path vazio = Files.createFile(ficheiro("zero.xlsx"));
        assertThrows(IOException.class, () -> ExcelLeitor.ler(vazio));
        IOException e2 = assertThrows(IOException.class, () -> ExcelLeitor.ler(ficheiro("nao-existe.xlsx")));
        assertTrue(e2.getMessage().contains("não existe"), e2.getMessage());
    }

    @Test
    void lerNuncaAlteraOFicheiroNemOFicaBloqueado() throws IOException {
        Path f = ficheiro("intacto.xlsx");
        ExcelEscritor.escrever(f, "D", List.of("A"), List.of(linha("x")));
        byte[] antes = Files.readAllBytes(f);
        var data = Files.getLastModifiedTime(f);
        ExcelLeitor.ler(f);
        assertArrayEquals(antes, Files.readAllBytes(f), "ler não pode regravar o ficheiro do utilizador");
        assertEquals(data, Files.getLastModifiedTime(f));
        Files.delete(f); // não ficou aberto
        assertFalse(Files.exists(f));
    }

    // ---- escrever ----------------------------------------------------------

    @Test
    void escreverSubstituiODestinoDeUmaSoVezESemDeixarTemporarios() throws IOException {
        Path f = ficheiro("saida.xlsx");
        ExcelEscritor.escrever(f, "D", List.of("A"), List.of(linha("primeiro")));
        ExcelEscritor.escrever(f, "D", List.of("A"), List.of(linha("segundo"), linha("terceiro")));
        assertEquals(2, ExcelLeitor.ler(f).linhas().size());
        try (Stream<Path> s = Files.list(tmp)) {
            assertEquals(List.of("saida.xlsx"), s.map(p -> p.getFileName().toString()).toList());
        }
        // pasta que não existe: falha limpa, sem criar nada
        assertThrows(IOException.class, () -> ExcelEscritor.escrever(tmp.resolve("nao/existe/x.xlsx"), "D", List.of("A"), List.of()));
    }

    @Test
    void formatacaoDoFicheiroGerado() throws Exception {
        Path f = ficheiro("formato.xlsx");
        List<List<Object>> linhas = new ArrayList<>();
        linhas.add(linha("curto", LocalDate.of(2026, 1, 1)));
        linhas.add(linha("x".repeat(500), null));
        ExcelEscritor.escrever(f, "Máquinas/[x]: y*?", List.of("Descrição", "Data de aquisição"), linhas);
        try (XSSFWorkbook livro = new XSSFWorkbook(OPCPackage.open(f.toFile(), PackageAccess.READ))) {
            XSSFSheet s = livro.getSheetAt(0);
            assertEquals("Máquinas  x   y", s.getSheetName(), "os caracteres que o Excel não aceita nos nomes saem");
            assertTrue(s.getPaneInformation().isFreezePane(), "a linha de cabeçalhos fica fixa");
            assertEquals("A1:B3", s.getCTWorksheet().getAutoFilter().getRef(), "filtros em todas as colunas");
            CellStyle estiloCabecalho = s.getRow(0).getCell(0).getCellStyle();
            assertTrue(livro.getFontAt(estiloCabecalho.getFontIndex()).getBold(), "cabeçalho a negrito");
            Cell d = s.getRow(1).getCell(1);
            assertEquals("dd/mm/yyyy", d.getCellStyle().getDataFormatString());
            assertTrue(s.getColumnWidth(0) <= 60 * 256, "largura limitada");
            assertTrue(s.getColumnWidth(0) > s.getColumnWidth(1), "a coluna do texto longo é a mais larga");
            assertEquals(500, s.getRow(2).getCell(0).getStringCellValue().length());
        }
        assertEquals("Dados", ExcelEscritor.nomeSeguro("  ///  "));
        assertEquals(31, ExcelEscritor.nomeSeguro("x".repeat(80)).length());
    }

    @Test
    void textoMaiorQueOLimiteDoExcelEcortadoEmVezDeFalhar() throws IOException {
        Path f = ficheiro("longo.xlsx");
        ExcelEscritor.escrever(f, "D", List.of("A"), List.of(linha("y".repeat(40_000))));
        assertEquals(32_767, ((String) ExcelLeitor.ler(f).linhas().get(0).valores().get(0)).length());
    }

    @Test
    void dezMilLinhasEscrevemELeemSeRapidamente() throws IOException {
        List<List<Object>> linhas = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            linhas.add(linha("COD-" + i, "Descrição " + i, i * 0.5, LocalDate.of(2020, 1, 1).plusDays(i % 1000), "un", 5.0, 12.0, "x"));
        }
        Path f = ficheiro("grande.xlsx");
        long t0 = System.nanoTime();
        ExcelEscritor.escrever(f, "Artigos", List.of("a", "b", "c", "d", "e", "f", "g", "h"), linhas);
        FolhaImportada folha = ExcelLeitor.ler(f);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(10_000, folha.linhas().size());
        assertEquals("COD-9999", folha.linhas().get(9_999).valores().get(0));
        assertEquals(10_001, folha.linhas().get(9_999).numero());
        assertTrue(ms < 20_000, "demorou " + ms + " ms");
    }

    @Test
    void ficheiroDemasiadoGrandeERecusadoAntesDeSerCarregado() throws IOException {
        Path grande = tmp.resolve("grande.xlsx");
        try (var f = new java.io.RandomAccessFile(grande.toFile(), "rw")) {
            f.setLength(ExcelLeitor.MAX_BYTES + 1); // ficheiro esparso: não ocupa disco
        }
        IOException e = assertThrows(IOException.class, () -> ExcelLeitor.ler(grande));
        assertTrue(e.getMessage().contains("demasiado grande"), e.getMessage());
    }
}
