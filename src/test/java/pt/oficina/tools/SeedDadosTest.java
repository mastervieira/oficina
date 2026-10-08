package pt.oficina.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pt.oficina.db.Database;
import pt.oficina.model.HistoricoEstado;
import pt.oficina.model.Maquina;
import pt.oficina.model.StockArtigo;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.service.EstadoMaquinaService;
import pt.oficina.service.InventarioService;
import pt.oficina.service.ManutencaoService;
import pt.oficina.service.StockService;

class SeedDadosTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 5);
    private static final SeedDados.Parametros PEQUENO = new SeedDados.Parametros(24, 60, 300, 42L, HOJE);

    private static SeedDados.Resumo gerar(Database db, SeedDados.Parametros p) throws SQLException {
        return SeedDados.gerar(db.connection(), p);
    }

    private static List<String> escalares(Connection c, String sql) throws SQLException {
        List<String> r = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                r.add(rs.getString(1));
            }
        }
        return r;
    }

    @Test
    void geraAsQuantidadesPedidasETodosOsEstados() throws SQLException {
        try (Database db = Database.inMemory()) {
            SeedDados.Resumo r = gerar(db, PEQUENO);
            assertEquals(24, r.maquinas());
            assertEquals(60, r.localizacoes());
            assertEquals(300, r.artigos());
            assertEquals(30, r.fornecedores());
            for (EstadoMaquina e : EstadoMaquina.values()) {
                assertTrue(r.porEstado().getOrDefault(e, 0) >= 1, "falta o estado " + e);
            }
            assertEquals(24, r.porEstado().values().stream().mapToInt(Integer::intValue).sum());
            assertTrue(r.movimentos() > 0 && r.planos() > 0 && r.intervencoes() > 0);
        }
    }

    @Test
    void localizacoesTemOFormatoPedidoESaoTodasDiferentes() throws SQLException {
        try (Database db = Database.inMemory()) {
            gerar(db, new SeedDados.Parametros(4, 500, 0, 1L, HOJE));
            List<String> nomes = escalares(db.connection(), "SELECT nome FROM localizacao ORDER BY id");
            assertEquals(500, nomes.size());
            assertEquals(500, new HashSet<>(nomes).size());
            assertEquals("Móvel A · Prateleira 1 · Secção A", nomes.get(0));
            assertEquals("Móvel A · Prateleira 1 · Secção C", nomes.get(2));
            assertEquals("Móvel J · Prateleira 10 · Secção E", nomes.get(499));
        }
    }

    @Test
    void artigosSaoVariadosECodigosEDescricoesNaoSeRepetem() throws SQLException {
        try (Database db = Database.inMemory()) {
            SeedDados.Resumo r = gerar(db, new SeedDados.Parametros(4, 10, 10_000, 7L, HOJE));
            assertEquals(10_000, r.artigos());
            Connection c = db.connection();
            assertEquals(10_000, new HashSet<>(escalares(c, "SELECT codigo FROM artigo")).size());
            assertEquals(10_000, new HashSet<>(escalares(c, "SELECT descricao FROM artigo")).size(),
                    "com 10 000 artigos, nenhuma descrição deve repetir-se");
            assertEquals("15", escalares(c, "SELECT COUNT(DISTINCT categoria_id) FROM artigo").get(0));
            assertEquals(Set.of("ARTIGO", "FERRAMENTA", "CONSUMIVEL", "OUTROS"),
                    new HashSet<>(escalares(c, "SELECT DISTINCT c.tipo FROM artigo a JOIN categoria c ON c.id = a.categoria_id")));
            assertTrue(Integer.parseInt(escalares(c, "SELECT COUNT(DISTINCT unidade) FROM artigo").get(0)) >= 2);
        }
    }

    @Test
    void contagensPorFamiliaSomamSempreOTotal() {
        for (int total : new int[] {0, 1, 7, 99, 300, 10_000, 12_345}) {
            assertEquals(total, java.util.Arrays.stream(SeedArtigos.contagens(total)).sum(), "total " + total);
        }
    }

    @Test
    void historicoDeEstadosEcoerenteComEstadoAtual() throws SQLException {
        try (Database db = Database.inMemory()) {
            gerar(db, PEQUENO);
            Connection c = db.connection();
            InventarioService inv = new InventarioService(c);
            EstadoMaquinaService estados = new EstadoMaquinaService(c);
            for (Maquina m : inv.maquinas()) {
                List<HistoricoEstado> h = estados.historico(m.id()); // mais recente primeiro
                assertEquals(m.estado(), h.get(0).estado(), m.codigo() + ": estado atual != último do histórico");
                HistoricoEstado inicial = h.get(h.size() - 1);
                assertEquals(EstadoMaquina.ATIVO, inicial.estado());
                assertEquals("Registo inicial", inicial.motivo());
                for (int i = 1; i < h.size(); i++) {
                    assertFalse(h.get(i).dataEstado().isAfter(h.get(i - 1).dataEstado()), m.codigo() + ": datas fora de ordem");
                    assertTrue(h.get(i - 1).estado() != h.get(i).estado(), m.codigo() + ": dois estados iguais seguidos");
                }
                assertFalse(h.get(0).dataEstado().isAfter(HOJE));
                if (m.dataAquisicao() != null) {
                    assertEquals(m.dataAquisicao(), inicial.dataEstado(), "o registo inicial é na data de aquisição");
                }
            }
        }
    }

    @Test
    void stockNuncaNegativoETemArtigosAbaixoDoMinimo() throws SQLException {
        try (Database db = Database.inMemory()) {
            SeedDados.Resumo r = gerar(db, PEQUENO);
            List<StockArtigo> stocks = new StockService(db.connection()).stockAtual();
            assertEquals(300, stocks.size());
            assertTrue(stocks.stream().allMatch(s -> s.stock() >= 0), "stock negativo");
            assertTrue(r.artigosAbaixoDoMinimo() > 0 && r.artigosAbaixoDoMinimo() < 300 / 2);
            assertEquals(r.artigosAbaixoDoMinimo(), stocks.stream().filter(StockArtigo::abaixoDoMinimo).count());
            // nenhum movimento no futuro
            assertEquals(List.of("0"), escalares(db.connection(),
                    "SELECT COUNT(*) FROM movimento_stock WHERE data_mov > '" + HOJE + "'"));
        }
    }

    @Test
    void manutencaoTemItinerarioSoDeMaquinasAtivasEIntervencoesNoPassado() throws SQLException {
        try (Database db = Database.inMemory()) {
            gerar(db, PEQUENO);
            Connection c = db.connection();
            var itens = new ManutencaoService(c, new StockService(c)).itinerario(HOJE);
            assertFalse(itens.isEmpty(), "o seed deve produzir itens no itinerário");
            assertTrue(itens.stream().allMatch(i -> i.maquina().estado() == EstadoMaquina.ATIVO));
            assertTrue(itens.stream().anyMatch(i -> i.prazo().vencido(HOJE)), "deve haver vencidos");
            assertTrue(itens.stream().anyMatch(i -> !i.prazo().vencido(HOJE)), "e a vencer");
            assertEquals(List.of("0"), escalares(c, "SELECT COUNT(*) FROM intervencao WHERE data_interv > '" + HOJE + "'"));
            // máquinas abatidas: nenhuma intervenção depois do abate
            assertEquals(List.of("0"), escalares(c,
                    "SELECT COUNT(*) FROM intervencao i JOIN historico_estado h ON h.maquina_id = i.maquina_id "
                            + "AND h.estado = 'ABATIDO' WHERE i.data_interv > h.data_estado"));
        }
    }

    @Test
    void osPlanosSaoPorTipoDeMaquinaEAsIntervencoesLigamSeAoPlanoDoTipo() throws SQLException {
        try (Database db = Database.inMemory()) {
            SeedDados.Resumo r = gerar(db, PEQUENO);
            Connection c = db.connection();
            // todos os planos são de categorias de máquinas, e cada tipo tem 3 a 5
            assertEquals(List.of("0"), escalares(c, "SELECT COUNT(*) FROM plano_manutencao p JOIN categoria t "
                    + "ON t.id = p.categoria_id WHERE t.tipo <> 'MAQUINA'"));
            assertEquals(List.of("8"), escalares(c, "SELECT COUNT(DISTINCT categoria_id) FROM plano_manutencao"));
            assertEquals(List.of("1"), escalares(c, "SELECT MIN(n) >= 3 AND MAX(n) <= 5 FROM "
                    + "(SELECT COUNT(*) AS n FROM plano_manutencao GROUP BY categoria_id)"));
            assertEquals(r.planos(), Integer.parseInt(escalares(c, "SELECT COUNT(*) FROM plano_manutencao").get(0)));
            // intervenções com plano: o plano é do tipo da máquina (o gatilho da base de dados também o garante)
            assertEquals(List.of("0"), escalares(c, "SELECT COUNT(*) FROM intervencao i JOIN plano_manutencao p ON p.id = i.plano_id "
                    + "JOIN maquina m ON m.id = i.maquina_id WHERE p.categoria_id <> m.categoria_id"));
            assertTrue(Integer.parseInt(escalares(c, "SELECT COUNT(*) FROM intervencao WHERE plano_id IS NOT NULL").get(0)) > 0);
            // várias máquinas do mesmo tipo usam o mesmo plano
            assertTrue(Integer.parseInt(escalares(c, "SELECT MAX(n) FROM (SELECT COUNT(DISTINCT maquina_id) AS n "
                    + "FROM intervencao WHERE plano_id IS NOT NULL GROUP BY plano_id)").get(0)) > 1);
        }
    }

    @Test
    void baseGeradaEIntegra() throws SQLException {
        try (Database db = Database.inMemory()) {
            gerar(db, PEQUENO);
            Connection c = db.connection();
            assertEquals(List.of("ok"), escalares(c, "PRAGMA integrity_check"));
            assertEquals(List.of(), escalares(c, "PRAGMA foreign_key_check"));
            assertEquals(List.of("1"), escalares(c, "PRAGMA foreign_keys"));
            assertTrue(c.getAutoCommit(), "a transação do seed foi fechada");
        }
    }

    @Test
    void mesmaSementeMesmosDadosSementeDiferenteOutros() throws SQLException {
        String tabelas = "SELECT group_concat(codigo || descricao || ifnull(localizacao_id,'') || ifnull(fornecedor_id,'') "
                + "|| stock_minimo, '|') FROM (SELECT * FROM artigo ORDER BY id)";
        String a;
        String b;
        String outra;
        try (Database db = Database.inMemory()) {
            gerar(db, PEQUENO);
            a = escalares(db.connection(), tabelas).get(0);
        }
        try (Database db = Database.inMemory()) {
            gerar(db, PEQUENO);
            b = escalares(db.connection(), tabelas).get(0);
        }
        try (Database db = Database.inMemory()) {
            gerar(db, new SeedDados.Parametros(24, 60, 300, 43L, HOJE));
            outra = escalares(db.connection(), tabelas).get(0);
        }
        assertEquals(a, b);
        assertTrue(!a.equals(outra));
    }

    @Test
    void parametrosInvalidosSaoRecusadosSemDeixarNada() throws SQLException {
        try (Database db = Database.inMemory()) {
            assertThrows(IllegalArgumentException.class, () -> gerar(db, new SeedDados.Parametros(3, 10, 10, 1L, HOJE)));
            assertThrows(IllegalArgumentException.class, () -> gerar(db, new SeedDados.Parametros(10, 0, 10, 1L, HOJE)));
            assertThrows(IllegalArgumentException.class, () -> gerar(db, new SeedDados.Parametros(10, 1301, 10, 1L, HOJE)));
            assertEquals(List.of("0"), escalares(db.connection(), "SELECT COUNT(*) FROM maquina"));
        }
    }

    @Test
    void umaFalhaADesfazerTudo() throws SQLException {
        try (Database db = Database.inMemory()) {
            gerar(db, new SeedDados.Parametros(4, 5, 5, 1L, HOJE));
            // Segunda geração na mesma base: os códigos já existem -> falha a meio -> nada fica a mais.
            assertThrows(RuntimeException.class, () -> gerar(db, new SeedDados.Parametros(4, 5, 5, 1L, HOJE)));
            assertEquals(List.of("4"), escalares(db.connection(), "SELECT COUNT(*) FROM maquina"));
            assertEquals(List.of("5"), escalares(db.connection(), "SELECT COUNT(*) FROM artigo"));
            assertTrue(db.connection().getAutoCommit());
            assertNull(escalares(db.connection(), "PRAGMA foreign_key_check").stream().findFirst().orElse(null));
        }
    }
}
