package pt.oficina.excel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pt.oficina.db.Database;
import pt.oficina.model.Artigo;
import pt.oficina.model.Maquina;
import pt.oficina.model.StockArtigo;
import pt.oficina.service.Catalogos;
import pt.oficina.service.FolhaImportada;
import pt.oficina.service.ImportacaoService;
import pt.oficina.service.InventarioService;
import pt.oficina.service.ResultadoImportacao;
import pt.oficina.service.StockService;
import pt.oficina.tools.SeedDados;

/** Exportar de uma base e importar para outra, com ficheiros .xlsx reais, tem de reproduzir os mesmos dados. */
class ExcelViagemTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 5);

    @TempDir
    Path tmp;

    private static Clock relogio() {
        ZoneId zona = ZoneId.systemDefault();
        return Clock.fixed(HOJE.atTime(12, 0).atZone(zona).toInstant(), zona);
    }

    /** Uma máquina descrita só por nomes (como no ecrã): comparável entre bases com ids diferentes. */
    private static Map<String, List<Object>> maquinasPorCodigo(InventarioService inv) throws SQLException {
        Catalogos cat = inv.catalogos();
        Map<String, List<Object>> r = new TreeMap<>();
        for (Maquina m : inv.maquinas()) {
            r.put(m.codigo(), java.util.Arrays.asList(m.descricao(), m.nSerie(), cat.categoria(m.categoriaId()),
                    cat.localizacao(m.localizacaoId()), cat.fornecedor(m.fornecedorId()), m.dataAquisicao(), m.estado()));
        }
        return r;
    }

    private static Map<String, List<Object>> artigosPorCodigo(InventarioService inv, StockService stock) throws SQLException {
        Catalogos cat = inv.catalogos();
        Map<String, List<Object>> r = new TreeMap<>();
        for (StockArtigo s : stock.stockAtual()) {
            Artigo a = s.artigo();
            r.put(a.codigo(), java.util.Arrays.asList(a.descricao(), cat.categoria(a.categoriaId()),
                    cat.localizacao(a.localizacaoId()), cat.fornecedor(a.fornecedorId()), a.unidade(), a.stockMinimo(),
                    s.stock()));
        }
        return r;
    }

    @Test
    void exportarEImportarReproduzOsMesmosDados() throws Exception {
        Path ficheiroMaquinas = tmp.resolve("maquinas.xlsx");
        Path ficheiroArtigos = tmp.resolve("artigos.xlsx");
        Map<String, List<Object>> maquinasOriginais;
        Map<String, List<Object>> artigosOriginais;
        try (Database origem = Database.inMemory()) {
            SeedDados.gerar(origem.connection(), new SeedDados.Parametros(30, 60, 600, 11L, HOJE));
            InventarioService inv = new InventarioService(origem.connection(), relogio());
            StockService stock = new StockService(origem.connection(), relogio());
            Catalogos cat = inv.catalogos();
            ExcelExportacao.maquinas(ficheiroMaquinas, inv.maquinas(), cat);
            ExcelExportacao.artigos(ficheiroArtigos, inv.artigos(),
                    stock.stockAtual().stream().collect(java.util.stream.Collectors.toMap(s -> s.artigo().id(), StockArtigo::stock)), cat);
            maquinasOriginais = maquinasPorCodigo(inv);
            artigosOriginais = artigosPorCodigo(inv, stock);
        }
        assertEquals(30, maquinasOriginais.size());
        assertEquals(600, artigosOriginais.size());

        try (Database destino = Database.inMemory()) {
            ImportacaoService imp = new ImportacaoService(destino.connection(), relogio());
            FolhaImportada folhaMaquinas = ExcelLeitor.ler(ficheiroMaquinas);
            FolhaImportada folhaArtigos = ExcelLeitor.ler(ficheiroArtigos);
            assertEquals(ExcelExportacao.TITULOS_MAQUINAS, folhaMaquinas.cabecalhos());
            assertEquals(ExcelExportacao.TITULOS_ARTIGOS, folhaArtigos.cabecalhos());

            ResultadoImportacao rm = imp.importarMaquinas(folhaMaquinas, false);
            ResultadoImportacao ra = imp.importarArtigos(folhaArtigos, false);
            assertTrue(rm.aplicada(), rm.erros().toString());
            assertTrue(ra.aplicada(), ra.erros().toString());
            assertEquals(30, rm.criados());
            assertEquals(600, ra.criados());

            InventarioService inv = new InventarioService(destino.connection(), relogio());
            StockService stock = new StockService(destino.connection(), relogio());
            assertEquals(maquinasOriginais, maquinasPorCodigo(inv), "máquinas: mesmos dados, incluindo o estado");
            assertEquals(artigosOriginais, artigosPorCodigo(inv, stock), "artigos: mesmos dados, incluindo o stock");

            // importar outra vez os mesmos ficheiros: nada a fazer, sem avisos nem listas novas
            ResultadoImportacao rm2 = imp.importarMaquinas(folhaMaquinas, false);
            ResultadoImportacao ra2 = imp.importarArtigos(folhaArtigos, false);
            assertEquals(30, rm2.semAlteracoes());
            assertEquals(600, ra2.semAlteracoes());
            assertEquals(0, rm2.criados() + rm2.atualizados() + ra2.criados() + ra2.atualizados());
            assertTrue(rm2.avisos().isEmpty() && ra2.avisos().isEmpty(), rm2.avisos() + " " + ra2.avisos());
            assertTrue(rm2.listasCriadas().isEmpty() && ra2.listasCriadas().isEmpty());
            assertEquals(artigosOriginais, artigosPorCodigo(inv, stock), "o stock não duplica ao reimportar");
        }
    }

    @Test
    void editarNoExcelEVoltarAImportarAtualiza() throws Exception {
        Path f = tmp.resolve("artigos.xlsx");
        try (Database db = Database.inMemory()) {
            SeedDados.gerar(db.connection(), new SeedDados.Parametros(4, 10, 40, 3L, HOJE));
            InventarioService inv = new InventarioService(db.connection(), relogio());
            StockService stock = new StockService(db.connection(), relogio());
            ExcelExportacao.artigos(f, inv.artigos(), stock.stockAtual().stream()
                    .collect(java.util.stream.Collectors.toMap(s -> s.artigo().id(), StockArtigo::stock)), inv.catalogos());

            // simula a edição no Excel: muda a descrição da 1.ª linha e acrescenta um artigo novo
            FolhaImportada lida = ExcelLeitor.ler(f);
            List<FolhaImportada.LinhaImportada> editadas = new ArrayList<>(lida.linhas());
            List<Object> primeira = new ArrayList<>(editadas.get(0).valores());
            primeira.set(1, "Descrição editada no Excel");
            editadas.set(0, new FolhaImportada.LinhaImportada(editadas.get(0).numero(), primeira));
            editadas.add(new FolhaImportada.LinhaImportada(99, java.util.Arrays.asList("NOVO-1", "Artigo novo", "Categoria nova",
                    null, null, "kg", 2.0, 7.0)));

            ResultadoImportacao r = new ImportacaoService(db.connection(), relogio())
                    .importarArtigos(new FolhaImportada(lida.cabecalhos(), editadas), false);
            assertTrue(r.aplicada(), r.erros().toString());
            assertEquals(1, r.criados());
            assertEquals(1, r.atualizados());
            assertEquals(39, r.semAlteracoes());
            assertEquals(Map.of("categorias", List.of("Categoria nova")), r.listasCriadas());
            Artigo novo = inv.artigos().stream().filter(a -> a.codigo().equals("NOVO-1")).findFirst().orElseThrow();
            assertEquals(7.0, stock.stockDe(novo.id()), 1e-9);
        }
    }
}
