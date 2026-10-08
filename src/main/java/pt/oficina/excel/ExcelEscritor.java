package pt.oficina.excel;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Escreve uma tabela num ficheiro .xlsx: cabeçalho a negrito, primeira linha fixa, filtros, datas como datas do
 * Excel e números como números. Grava primeiro num ficheiro temporário e só depois o move para o destino, por isso
 * um destino que já exista é substituído de uma só vez e nunca fica um ficheiro a meio.
 */
public final class ExcelEscritor {

    private static final int LARGURA_MAXIMA = 60; // em caracteres
    private static final int LARGURA_MINIMA = 8;
    private static final int MAX_TEXTO = 32_767; // limite de uma célula do Excel

    private ExcelEscritor() {
    }

    /** Cada valor é String, Number, LocalDate, Boolean ou null (célula vazia). */
    public static void escrever(Path destino, String nomeFolha, List<String> titulos, List<List<Object>> linhas)
            throws IOException {
        Path temporario = destino.resolveSibling(destino.getFileName() + ".tmp");
        try {
            try (XSSFWorkbook livro = new XSSFWorkbook(); OutputStream saida = Files.newOutputStream(temporario)) {
                Sheet folha = livro.createSheet(nomeSeguro(nomeFolha));
                CellStyle estiloCabecalho = estiloCabecalho(livro);
                CellStyle estiloData = livro.createCellStyle();
                estiloData.setDataFormat(livro.createDataFormat().getFormat("dd/mm/yyyy"));

                int colunas = titulos.size();
                int[] larguras = new int[colunas];
                Row cabecalho = folha.createRow(0);
                for (int i = 0; i < colunas; i++) {
                    Cell c = cabecalho.createCell(i);
                    c.setCellValue(titulos.get(i));
                    c.setCellStyle(estiloCabecalho);
                    larguras[i] = titulos.get(i).length() + 2; // a negrito ocupa mais
                }
                for (int r = 0; r < linhas.size(); r++) {
                    Row linha = folha.createRow(r + 1);
                    List<Object> valores = linhas.get(r);
                    for (int i = 0; i < colunas && i < valores.size(); i++) {
                        larguras[i] = Math.max(larguras[i], escreverCelula(linha, i, valores.get(i), estiloData));
                    }
                }
                folha.createFreezePane(0, 1);
                if (colunas > 0) {
                    folha.setAutoFilter(new CellRangeAddress(0, Math.max(linhas.size(), 1), 0, colunas - 1));
                }
                for (int i = 0; i < colunas; i++) {
                    int caracteres = Math.min(LARGURA_MAXIMA, Math.max(LARGURA_MINIMA, larguras[i] + 1));
                    folha.setColumnWidth(i, caracteres * 256);
                }
                livro.write(saida);
            }
            mover(temporario, destino);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporario);
            throw e;
        }
    }

    /** @return o nº de caracteres que o valor ocupa, para dimensionar a coluna */
    private static int escreverCelula(Row linha, int coluna, Object valor, CellStyle estiloData) {
        if (valor == null) {
            return 0;
        }
        Cell c = linha.createCell(coluna);
        if (valor instanceof LocalDate d) {
            c.setCellValue(d);
            c.setCellStyle(estiloData);
            return 10;
        }
        if (valor instanceof Number n) {
            c.setCellValue(n.doubleValue());
            return String.valueOf(n).length();
        }
        if (valor instanceof Boolean b) {
            c.setCellValue(b);
            return 5;
        }
        String texto = valor.toString();
        if (texto.length() > MAX_TEXTO) {
            texto = texto.substring(0, MAX_TEXTO);
        }
        c.setCellValue(texto); // sempre como texto: nunca se interpreta como fórmula
        return texto.length();
    }

    private static CellStyle estiloCabecalho(XSSFWorkbook livro) {
        Font negrito = livro.createFont();
        negrito.setBold(true);
        CellStyle estilo = livro.createCellStyle();
        estilo.setFont(negrito);
        estilo.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        estilo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        estilo.setBorderBottom(BorderStyle.THIN);
        return estilo;
    }

    /** O Excel não aceita [ ] : * ? / \ nos nomes das folhas, nem mais de 31 caracteres. */
    static String nomeSeguro(String nome) {
        String limpo = nome.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (limpo.isEmpty()) {
            limpo = "Dados";
        }
        return limpo.length() > 31 ? limpo.substring(0, 31) : limpo;
    }

    private static void mover(Path de, Path para) throws IOException {
        try {
            Files.move(de, para, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(de, para, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
