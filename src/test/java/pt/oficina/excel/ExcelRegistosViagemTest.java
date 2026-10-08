package pt.oficina.excel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pt.oficina.db.Database;
import pt.oficina.model.Artigo;
import pt.oficina.model.Maquina;
import pt.oficina.model.PlanoManutencao;
import pt.oficina.model.StockArtigo;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.service.Catalogos;
import pt.oficina.service.EstadoMaquinaService;
import pt.oficina.service.FolhaImportada;
import pt.oficina.service.FolhaImportada.LinhaImportada;
import pt.oficina.service.ImportacaoService;
import pt.oficina.service.InventarioService;
import pt.oficina.service.ManutencaoService;
import pt.oficina.service.ResultadoImportacao;
import pt.oficina.service.StockService;
import pt.oficina.tools.SeedDados;

/**
 * Movimentos, intervenções e estados: exportar com ficheiros .xlsx reais e voltar a importar tem de reproduzir os
 * registos, e os cabeçalhos escritos têm de ser os que a importação reconhece.
 */
class ExcelRegistosViagemTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 5);

    @TempDir
    Path tmp;

    private static Clock relogio() {
        ZoneId zona = ZoneId.systemDefault();
        return Clock.fixed(HOJE.atTime(12, 0).atZone(zona).toInstant(), zona);
    }

    private static Map<Long, Maquina> maquinasPorId(InventarioService inv) throws SQLException {
        Map<Long, Maquina> r = new HashMap<>();
        inv.maquinas().forEach(m -> r.put(m.id(), m));
        return r;
    }

    private static Map<Long, Artigo> artigosPorId(InventarioService inv) throws SQLException {
        Map<Long, Artigo> r = new HashMap<>();
        inv.artigos().forEach(a -> r.put(a.id(), a));
        return r;
    }

    private static Map<String, Double> stocksPorCodigo(StockService stock) throws SQLException {
        Map<String, Double> r = new TreeMap<>();
        for (StockArtigo s : stock.stockAtual()) {
            r.put(s.artigo().codigo(), s.stock());
        }
        return r;
    }

    private static FolhaImportada comValor(FolhaImportada f, String coluna, Object valor) {
        int i = f.cabecalhos().indexOf(coluna);
        List<LinhaImportada> linhas = new ArrayList<>();
        for (LinhaImportada l : f.linhas()) {
            List<Object> v = new ArrayList<>(l.valores());
            v.set(i, valor);
            linhas.add(new LinhaImportada(l.numero(), v));
        }
        return new FolhaImportada(f.cabecalhos(), linhas);
    }

    @Test
    void movimentosEIntervencoesExportadosVoltamAImportarSeNumaBaseSoComOInventario() throws Exception {
        Path ficheiroMaquinas = tmp.resolve("maquinas.xlsx");
        Path ficheiroArtigos = tmp.resolve("artigos.xlsx");
        Path ficheiroMovimentos = tmp.resolve("movimentos.xlsx");
        Path ficheiroIntervencoes = tmp.resolve("intervencoes.xlsx");
        Map<String, Double> stocksOriginais;
        List<List<Object>> movimentosOriginais = new ArrayList<>();
        List<List<Object>> intervencoesOriginais = new ArrayList<>();
        List<String[]> planosOriginais = new ArrayList<>();

        try (Database origem = Database.inMemory()) {
            SeedDados.gerar(origem.connection(), new SeedDados.Parametros(30, 60, 600, 11L, HOJE));
            InventarioService inv = new InventarioService(origem.connection(), relogio());
            StockService stock = new StockService(origem.connection(), relogio());
            ManutencaoService manutencao = new ManutencaoService(origem.connection(), stock, relogio());
            Catalogos cat = inv.catalogos();
            ExcelExportacao.maquinas(ficheiroMaquinas, inv.maquinas(), cat);
            ExcelExportacao.artigos(ficheiroArtigos, inv.artigos(), Map.of(), cat); // sem stock: vem dos movimentos
            ExcelExportacao.movimentos(ficheiroMovimentos, stock.movimentos(null), artigosPorId(inv));
            ExcelExportacao.intervencoes(ficheiroIntervencoes, manutencao.intervencoes(), maquinasPorId(inv),
                    manutencao.planosDeManutencao().stream().collect(Collectors.toMap(PlanoManutencao::id, p -> p)));

            stocksOriginais = stocksPorCodigo(stock);
            Map<Long, Artigo> artigos = artigosPorId(inv);
            stock.movimentos(null).forEach(m -> movimentosOriginais.add(java.util.Arrays.asList(
                    artigos.get(m.artigoId()).codigo(), m.dataMov(), m.tipo(), m.quantidade(), m.nota())));
            Map<Long, Maquina> maquinas = maquinasPorId(inv);
            Map<Long, PlanoManutencao> planos = manutencao.planosDeManutencao().stream()
                    .collect(Collectors.toMap(PlanoManutencao::id, p -> p));
            manutencao.intervencoes().forEach(i -> intervencoesOriginais.add(java.util.Arrays.asList(
                    maquinas.get(i.maquinaId()).codigo(), i.dataInterv(), i.tipo(),
                    i.planoId() == null ? null : planos.get(i.planoId()).tarefa(), i.descricao(), i.custo())));
            for (PlanoManutencao p : manutencao.planosDeManutencao()) {
                planosOriginais.add(new String[] {cat.categoria(p.categoriaId()), p.tarefa(),
                        String.valueOf(p.periodicidadeMeses())});
            }
        }
        assertFalse(movimentosOriginais.isEmpty());
        assertFalse(intervencoesOriginais.isEmpty());

        FolhaImportada folhaMovimentos = ExcelLeitor.ler(ficheiroMovimentos);
        FolhaImportada folhaIntervencoes = ExcelLeitor.ler(ficheiroIntervencoes);
        assertEquals(ExcelExportacao.TITULOS_MOVIMENTOS, folhaMovimentos.cabecalhos());
        assertEquals(ExcelExportacao.TITULOS_INTERVENCOES, folhaIntervencoes.cabecalhos());

        try (Database destino = Database.inMemory()) {
            ImportacaoService imp = new ImportacaoService(destino.connection(), relogio());
            assertTrue(imp.importarMaquinas(ExcelLeitor.ler(ficheiroMaquinas), false).aplicada());
            assertTrue(imp.importarArtigos(ExcelLeitor.ler(ficheiroArtigos), false).aplicada());
            InventarioService inv = new InventarioService(destino.connection(), relogio());
            StockService stock = new StockService(destino.connection(), relogio());
            ManutencaoService manutencao = new ManutencaoService(destino.connection(), stock, relogio());
            Catalogos cat = inv.catalogos();
            Map<String, Long> tipos = new HashMap<>();
            cat.categoriasMaquina().forEach(c -> tipos.put(c.nome(), c.id()));
            for (String[] p : planosOriginais) {
                long tipo = tipos.containsKey(p[0]) ? tipos.get(p[0])
                        : inv.criarCategoria(p[0], pt.oficina.model.enums.TipoCategoria.MAQUINA).id();
                tipos.put(p[0], tipo);
                manutencao.guardarPlano(new PlanoManutencao(null, tipo, p[1], Integer.parseInt(p[2])));
            }

            // os movimentos: exatamente os que havia, e por isso o mesmo stock
            ResultadoImportacao rm = imp.importarMovimentos(folhaMovimentos, false);
            assertTrue(rm.aplicada(), rm.erros().toString());
            assertEquals(movimentosOriginais.size(), rm.criados());
            assertTrue(rm.avisos().isEmpty(), rm.avisos().toString());
            assertEquals(stocksOriginais, stocksPorCodigo(stock), "o mesmo stock, calculado pelos movimentos");
            Map<Long, Artigo> artigos = artigosPorId(inv);
            List<List<Object>> movimentosDestino = new ArrayList<>();
            stock.movimentos(null).forEach(m -> movimentosDestino.add(java.util.Arrays.asList(
                    artigos.get(m.artigoId()).codigo(), m.dataMov(), m.tipo(), m.quantidade(), m.nota())));
            assertEquals(ordenado(movimentosOriginais), ordenado(movimentosDestino));

            // as intervenções, ligadas aos planos certos
            ResultadoImportacao ri = imp.importarIntervencoes(folhaIntervencoes, false);
            assertTrue(ri.aplicada(), ri.erros().toString());
            assertEquals(intervencoesOriginais.size(), ri.criados());
            Map<Long, Maquina> maquinas = maquinasPorId(inv);
            Map<Long, PlanoManutencao> planos = manutencao.planosDeManutencao().stream()
                    .collect(Collectors.toMap(PlanoManutencao::id, p -> p));
            List<List<Object>> intervencoesDestino = new ArrayList<>();
            manutencao.intervencoes().forEach(i -> intervencoesDestino.add(java.util.Arrays.asList(
                    maquinas.get(i.maquinaId()).codigo(), i.dataInterv(), i.tipo(),
                    i.planoId() == null ? null : planos.get(i.planoId()).tarefa(), i.descricao(), i.custo())));
            assertEquals(ordenado(intervencoesOriginais), ordenado(intervencoesDestino));

            // reimportar os mesmos ficheiros duplicaria tudo: a importação avisa (e a simulação não grava)
            ResultadoImportacao rm2 = imp.importarMovimentos(folhaMovimentos, true);
            ResultadoImportacao ri2 = imp.importarIntervencoes(folhaIntervencoes, true);
            assertEquals(1, rm2.avisos().size(), rm2.avisos().toString());
            assertEquals(1, ri2.avisos().size(), ri2.avisos().toString());
            assertTrue(rm2.avisos().get(0).startsWith(movimentosOriginais.size() + " linhas coincidem"), rm2.avisos().get(0));
            assertTrue(ri2.avisos().get(0).startsWith(intervencoesOriginais.size() + " linhas coincidem"), ri2.avisos().get(0));
            assertEquals(stocksOriginais, stocksPorCodigo(stock));
        }
    }

    /** Compara conjuntos de registos sem depender da ordem (as listas vêm do mais recente para o mais antigo). */
    private static List<String> ordenado(List<List<Object>> registos) {
        return registos.stream().map(Object::toString).sorted().toList();
    }

    @Test
    void estadosExportadosReimportadosNaoMudamNada_ESoMudaOQueSeEditou() throws Exception {
        Path ficheiro = tmp.resolve("estados.xlsx");
        try (Database db = Database.inMemory()) {
            SeedDados.gerar(db.connection(), new SeedDados.Parametros(30, 20, 40, 5L, HOJE));
            InventarioService inv = new InventarioService(db.connection(), relogio());
            EstadoMaquinaService estados = new EstadoMaquinaService(db.connection(), relogio());
            ExcelExportacao.estados(ficheiro, inv.maquinas(), estados.desde(), inv.catalogos());
            FolhaImportada lida = ExcelLeitor.ler(ficheiro);
            assertEquals(ExcelExportacao.TITULOS_ESTADOS, lida.cabecalhos());
            assertEquals(30, lida.linhas().size());

            ImportacaoService imp = new ImportacaoService(db.connection(), relogio());
            ResultadoImportacao igual = imp.importarEstados(lida, false);
            assertTrue(igual.aplicada(), igual.erros().toString());
            assertEquals(30, igual.semAlteracoes());
            assertEquals(0, igual.criados());
            assertFalse(igual.temAlteracoes());

            // edita uma linha no «Excel»: a 1.ª máquina passa a Abatido (se já o for, a 2.ª)
            int iEstado = lida.cabecalhos().indexOf("Estado");
            int iCodigo = lida.cabecalhos().indexOf("Código");
            List<LinhaImportada> editadas = new ArrayList<>(lida.linhas());
            int alvo = 0;
            while (EstadoMaquina.ABATIDO.toString().equals(editadas.get(alvo).valores().get(iEstado))) {
                alvo++;
            }
            String codigo = String.valueOf(editadas.get(alvo).valores().get(iCodigo));
            List<Object> v = new ArrayList<>(editadas.get(alvo).valores());
            v.set(iEstado, "Abatido");
            editadas.set(alvo, new LinhaImportada(editadas.get(alvo).numero(), v));

            ResultadoImportacao r = imp.importarEstados(new FolhaImportada(lida.cabecalhos(), editadas), false);
            assertTrue(r.aplicada(), r.erros().toString());
            assertEquals(1, r.criados());
            assertEquals(29, r.semAlteracoes());
            Maquina m = inv.maquinas().stream().filter(x -> x.codigo().equals(codigo)).findFirst().orElseThrow();
            assertEquals(EstadoMaquina.ABATIDO, m.estado());
            assertEquals(HOJE, estados.historico(m.id()).get(0).dataEstado());
        }
    }

    @Test
    void asRestantesFolhasEscrevemOsTitulosEAsLinhasDoEcra() throws Exception {
        try (Database db = Database.inMemory()) {
            SeedDados.gerar(db.connection(), new SeedDados.Parametros(12, 10, 30, 2L, HOJE));
            InventarioService inv = new InventarioService(db.connection(), relogio());
            StockService stock = new StockService(db.connection(), relogio());
            ManutencaoService manutencao = new ManutencaoService(db.connection(), stock, relogio());
            EstadoMaquinaService estados = new EstadoMaquinaService(db.connection(), relogio());
            Catalogos cat = inv.catalogos();

            Path fStock = tmp.resolve("stock.xlsx");
            ExcelExportacao.stockAtual(fStock, stock.stockAtual(), cat);
            FolhaImportada s = ExcelLeitor.ler(fStock);
            assertEquals(ExcelExportacao.TITULOS_STOCK, s.cabecalhos());
            assertEquals(30, s.linhas().size());
            long abaixo = stock.alertas().size();
            int iAlerta = s.cabecalhos().indexOf("Alerta");
            assertEquals(abaixo, s.linhas().stream()
                    .filter(l -> iAlerta < l.valores().size() && "Abaixo do mínimo".equals(l.valores().get(iAlerta))).count());

            Path fPlanos = tmp.resolve("planos.xlsx");
            ExcelExportacao.planos(fPlanos, "Planos", manutencao.planosDasMaquinas(), HOJE, cat);
            FolhaImportada p = ExcelLeitor.ler(fPlanos);
            assertEquals(ExcelExportacao.TITULOS_PLANOS, p.cabecalhos());
            assertEquals(manutencao.planosDasMaquinas().size(), p.linhas().size());
            assertFalse(p.linhas().isEmpty());
            int iSituacao = p.cabecalhos().indexOf("Situação");
            assertTrue(p.linhas().stream().allMatch(l -> l.valores().get(iSituacao) != null));

            Path fItinerario = tmp.resolve("itinerario.xlsx");
            ExcelExportacao.planos(fItinerario, "Itinerário", manutencao.itinerario(HOJE), HOJE, cat);
            assertEquals(manutencao.itinerario(HOJE).size(), ExcelLeitor.ler(fItinerario).linhas().size());

            Path fHistorico = tmp.resolve("historico.xlsx");
            var todo = estados.historicoCompleto();
            ExcelExportacao.historico(fHistorico, todo, maquinasPorId(inv));
            FolhaImportada h = ExcelLeitor.ler(fHistorico);
            assertEquals(ExcelExportacao.TITULOS_HISTORICO, h.cabecalhos());
            assertEquals(todo.size(), h.linhas().size());
            // por máquina e do mais antigo para o mais recente
            int iData = h.cabecalhos().indexOf("Data");
            int iCodigo = h.cabecalhos().indexOf("Código");
            for (int i = 1; i < h.linhas().size(); i++) {
                Object c0 = h.linhas().get(i - 1).valores().get(iCodigo);
                Object c1 = h.linhas().get(i).valores().get(iCodigo);
                if (c0.equals(c1)) {
                    LocalDate d0 = (LocalDate) h.linhas().get(i - 1).valores().get(iData);
                    LocalDate d1 = (LocalDate) h.linhas().get(i).valores().get(iData);
                    assertFalse(d1.isBefore(d0), c0 + ": " + d0 + " depois " + d1);
                }
            }
        }
    }
}
