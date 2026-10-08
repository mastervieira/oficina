package pt.oficina.excel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCell;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType;
import pt.oficina.service.FolhaImportada;
import pt.oficina.service.FolhaImportada.LinhaImportada;

/**
 * Lê a primeira folha de um ficheiro .xlsx: a primeira linha com conteúdo é entregue como cabeçalhos e as seguintes
 * como dados (as linhas totalmente vazias saltam-se). Quem usa a folha decide qual é, de facto, a linha de cabeçalhos
 * (pode haver um título por cima). Datas do Excel dão {@link LocalDate}; números dão {@link Double};
 * fórmulas dão o último valor calculado.
 *
 * <p>O ficheiro abre-se só para leitura: com acesso de escrita, fechá-lo regravaria o ficheiro do utilizador.
 */
public final class ExcelLeitor {

    public static final int MAX_LINHAS = 200_000;
    public static final int MAX_COLUNAS = 200;
    /**
     * Tamanho máximo do ficheiro. O POI carrega a folha inteira em memória (várias vezes o tamanho do ficheiro) antes de
     * se poderem contar as linhas: sem este limite, um ficheiro enorme esgotava a memória da aplicação.
     */
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    private ExcelLeitor() {
    }

    public static FolhaImportada ler(Path ficheiro) throws IOException {
        String nome = ficheiro.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!nome.endsWith(".xlsx")) {
            throw new IOException("Só se aceitam ficheiros .xlsx. Se o ficheiro for .xls ou .csv, abra-o no Excel e "
                    + "guarde-o como «Livro do Excel (.xlsx)».");
        }
        if (!Files.isRegularFile(ficheiro)) {
            throw new IOException("O ficheiro não existe: " + ficheiro);
        }
        if (Files.size(ficheiro) > MAX_BYTES) {
            throw new IOException("O ficheiro é demasiado grande (" + Files.size(ficheiro) / (1024 * 1024) + " MB; máximo "
                    + MAX_BYTES / (1024 * 1024) + " MB). Divida-o em vários ficheiros mais pequenos.");
        }
        OPCPackage pacote = null;
        try {
            pacote = OPCPackage.open(ficheiro.toFile(), PackageAccess.READ);
            try (XSSFWorkbook livro = new XSSFWorkbook(pacote)) {
                pacote = null; // passou a ser o livro a fechá-lo
                if (livro.getNumberOfSheets() == 0) {
                    return new FolhaImportada(List.of(), List.of());
                }
                return lerFolha(livro.getSheetAt(0));
            }
        } catch (InvalidFormatException | RuntimeException e) {
            throw new IOException("Não foi possível ler o ficheiro Excel (está corrompido, protegido por palavra-passe "
                    + "ou não é um .xlsx válido): " + e.getMessage(), e);
        } finally {
            if (pacote != null) {
                pacote.revert(); // o livro não chegou a ser criado: libertar o ficheiro
            }
        }
    }

    private static FolhaImportada lerFolha(Sheet folha) throws IOException {
        if (folha.getLastRowNum() - folha.getFirstRowNum() > MAX_LINHAS) {
            throw new IOException("O ficheiro tem demasiadas linhas (máximo " + MAX_LINHAS + ").");
        }
        List<String> cabecalhos = null;
        List<LinhaImportada> linhas = new ArrayList<>();
        for (int r = folha.getFirstRowNum(); r <= folha.getLastRowNum(); r++) {
            Row linha = folha.getRow(r);
            if (linha == null) {
                continue;
            }
            if (cabecalhos == null) {
                List<Object> valores = valores(linha, MAX_COLUNAS);
                if (!vazia(valores)) {
                    cabecalhos = valores.stream().map(v -> v == null ? "" : String.valueOf(v)).toList();
                }
                continue;
            }
            List<Object> valores = valores(linha, MAX_COLUNAS);
            if (!vazia(valores)) {
                linhas.add(new LinhaImportada(r + 1, valores));
            }
        }
        return new FolhaImportada(cabecalhos == null ? List.of() : cabecalhos, linhas);
    }

    private static List<Object> valores(Row linha, int limite) {
        int n = Math.min(Math.max(linha.getLastCellNum(), 0), limite);
        List<Object> r = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            r.add(valor(linha.getCell(i)));
        }
        return r;
    }

    private static boolean vazia(List<Object> valores) {
        return valores.stream().allMatch(v -> v == null || (v instanceof String s && s.isBlank()));
    }

    /**
     * Fórmula cujo resultado nunca foi calculado: sem {@code <v>} ou com {@code <v>} vazio (como escrevem alguns
     * programas). Um resultado de texto vazio ({@code t="str"}) é legítimo e não conta.
     */
    private static boolean semValorEmCache(XSSFCell celula) {
        CTCell xml = celula.getCTCell();
        boolean vazio = !xml.isSetV() || xml.getV() == null || xml.getV().isBlank();
        return vazio && xml.getT() != STCellType.STR;
    }

    private static Object valor(Cell celula) {
        if (celula == null) {
            return null;
        }
        if (celula.getCellType() == CellType.FORMULA && celula instanceof XSSFCell x && semValorEmCache(x)) {
            return FolhaImportada.FORMULA_SEM_VALOR; // o POI daria 0 em silêncio
        }
        CellType tipo = celula.getCellType() == CellType.FORMULA ? celula.getCachedFormulaResultType() : celula.getCellType();
        return switch (tipo) {
            case STRING -> celula.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(celula)) {
                    LocalDateTime d = celula.getLocalDateTimeCellValue();
                    yield d == null ? null : d.toLocalDate();
                }
                yield celula.getNumericCellValue();
            }
            case BOOLEAN -> celula.getBooleanCellValue();
            default -> null; // vazia ou erro (#N/D, #REF!, ...)
        };
    }
}
