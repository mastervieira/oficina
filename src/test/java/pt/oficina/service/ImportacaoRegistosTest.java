package pt.oficina.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.oficina.db.Database;
import pt.oficina.model.Artigo;
import pt.oficina.model.Categoria;
import pt.oficina.model.ConsumoMaterial;
import pt.oficina.model.Intervencao;
import pt.oficina.model.Maquina;
import pt.oficina.model.PlanoManutencao;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.model.enums.TipoCategoria;
import pt.oficina.model.enums.TipoIntervencao;
import pt.oficina.model.enums.TipoMovimento;
import pt.oficina.service.FolhaImportada.LinhaImportada;

/** Importar movimentos de stock, intervenções e mudanças de estado: registos que só se acrescentam. */
class ImportacaoRegistosTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 5);

    private Database db;
    private InventarioService inv;
    private StockService stock;
    private EstadoMaquinaService estados;
    private ManutencaoService manutencao;
    private ImportacaoService imp;

    @BeforeEach
    void preparar() throws SQLException {
        db = Database.inMemory();
        // as máquinas nascem há 60 dias: o histórico de estados começa nessa data e as mudanças importadas são posteriores
        inv = new InventarioService(db.connection(), Relogios.em(HOJE.minusDays(60)));
        stock = new StockService(db.connection(), Relogios.em(HOJE));
        estados = new EstadoMaquinaService(db.connection(), Relogios.em(HOJE));
        manutencao = new ManutencaoService(db.connection(), stock, Relogios.em(HOJE));
        imp = new ImportacaoService(db.connection(), Relogios.em(HOJE));
    }

    @AfterEach
    void fechar() throws SQLException {
        db.close();
    }

    // ---- utilitários -------------------------------------------------------

    private static FolhaImportada folha(List<String> cabecalhos, Object[]... linhas) {
        List<LinhaImportada> ls = new ArrayList<>();
        for (int i = 0; i < linhas.length; i++) {
            ls.add(new LinhaImportada(i + 2, Arrays.asList(linhas[i])));
        }
        return new FolhaImportada(cabecalhos, ls);
    }

    private static Object[] l(Object... valores) {
        return valores;
    }

    private static final List<String> CAB_MOV = List.of("Código", "Data", "Tipo", "Quantidade", "Nota");
    private static final List<String> CAB_INT = List.of("Código", "Data", "Tipo", "Plano", "Descrição", "Custo");
    private static final List<String> CAB_EST = List.of("Código", "Estado", "Data", "Motivo");

    private int contar(String sql) throws SQLException {
        try (Statement st = db.connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private List<String> mensagens(ResultadoImportacao r) {
        return r.erros().stream().map(e -> e.linha() + ": " + e.mensagem()).collect(Collectors.toList());
    }

    private Artigo novoArtigo(String codigo, double stockInicial) throws SQLException {
        Categoria cat = inv.catalogos().categoriasArtigo().stream().findFirst()
                .orElseGet(() -> semExcecao(() -> inv.criarCategoria("Rolamentos", TipoCategoria.ARTIGO)));
        Artigo a = inv.guardarArtigo(new Artigo(null, codigo, "Artigo " + codigo, cat.id(), null, null, "un", 0));
        if (stockInicial > 0) {
            stock.registar(a.id(), TipoMovimento.ENTRADA, HOJE.minusDays(100), stockInicial, "Inicial");
        }
        return a;
    }

    private Maquina novaMaquina(String codigo) throws SQLException {
        return novaMaquina(codigo, "Tornos");
    }

    private Maquina novaMaquina(String codigo, String tipo) throws SQLException {
        Categoria cat = inv.catalogos().categoriasMaquina().stream().filter(c -> c.nome().equals(tipo)).findFirst()
                .orElseGet(() -> semExcecao(() -> inv.criarCategoria(tipo, TipoCategoria.MAQUINA)));
        return inv.guardarMaquina(new Maquina(null, codigo, "Máquina " + codigo, null, cat.id(), null, null, null, null));
    }

    private PlanoManutencao plano(Maquina m, String tarefa, int meses) throws SQLException {
        return manutencao.guardarPlano(new PlanoManutencao(null, m.categoriaId(), tarefa, meses));
    }

    @FunctionalInterface
    private interface Acao<T> {
        T executar() throws SQLException;
    }

    private static <T> T semExcecao(Acao<T> a) {
        try {
            return a.executar();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- movimentos --------------------------------------------------------

    @Test
    void registaMovimentosDeArtigosExistentes() throws SQLException {
        Artigo a = novoArtigo("A1", 10);
        Artigo b = novoArtigo("B1", 0);

        ResultadoImportacao r = imp.importarMovimentos(folha(CAB_MOV,
                l("a1", LocalDate.of(2026, 10, 1), "Entrada", 5.0, "Fatura 123"),
                l("A1", LocalDate.of(2026, 10, 2), "saída", 3.5, null),
                l("B1", "03/10/2026", "ENTRADA", "2,5", "  duas   caixas ")), false);

        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(3, r.criados());
        assertEquals(11.5, stock.stockDe(a.id()), 1e-9);
        assertEquals(2.5, stock.stockDe(b.id()), 1e-9);
        var movB = stock.movimentos(b.id()).get(0);
        assertEquals(LocalDate.of(2026, 10, 3), movB.dataMov());
        assertEquals("duas caixas", movB.nota());
    }

    @Test
    void umaSaidaPodeVirNoFicheiroAntesDaEntradaQueACobre() throws SQLException {
        Artigo a = novoArtigo("A1", 0);

        // no ficheiro a saída vem primeiro, mas a entrada é anterior
        ResultadoImportacao r = imp.importarMovimentos(folha(CAB_MOV,
                l("A1", LocalDate.of(2026, 10, 3), "Saída", 8.0, null),
                l("A1", LocalDate.of(2026, 10, 1), "Entrada", 10.0, null)), false);

        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(2.0, stock.stockDe(a.id()), 1e-9);
    }

    @Test
    void umaEntradaPosteriorACobrirUmaSaidaAnteriorTambemServe() throws SQLException {
        Artigo a = novoArtigo("A1", 0);

        // o stock só se confere pelo total (como ao registar à mão): importar um exportado, que vem do mais recente
        // para o mais antigo, não pode falhar por causa da ordem
        ResultadoImportacao r = imp.importarMovimentos(folha(CAB_MOV,
                l("A1", LocalDate.of(2026, 10, 4), "Entrada", 10.0, null),
                l("A1", LocalDate.of(2026, 10, 2), "Saída", 6.0, null)), false);

        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(4.0, stock.stockDe(a.id()), 1e-9);
    }

    @Test
    void entradaESaidaNoMesmoDiaNaoDependemDaOrdemDoFicheiro() throws SQLException {
        Artigo a = novoArtigo("A1", 0);
        LocalDate dia = LocalDate.of(2026, 10, 1);

        ResultadoImportacao r = imp.importarMovimentos(folha(CAB_MOV,
                l("A1", dia, "Saída", 4.0, null),
                l("A1", dia, "Entrada", 4.0, null)), false);

        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(0.0, stock.stockDe(a.id()), 1e-9);
    }

    @Test
    void saidaSemStockImpedeTudo() throws SQLException {
        Artigo a = novoArtigo("A1", 2);
        Artigo b = novoArtigo("B1", 0);

        ResultadoImportacao r = imp.importarMovimentos(folha(CAB_MOV,
                l("B1", LocalDate.of(2026, 10, 1), "Entrada", 5.0, null),
                l("A1", LocalDate.of(2026, 10, 2), "Saída", 3.0, null)), false);

        assertFalse(r.aplicada());
        assertEquals(1, r.erros().size());
        assertEquals(3, r.erros().get(0).linha(), "a linha do Excel da saída, não a ordem de registo");
        assertTrue(r.erros().get(0).mensagem().contains("Stock insuficiente de A1"), r.erros().get(0).mensagem());
        assertEquals(2.0, stock.stockDe(a.id()), 1e-9);
        assertEquals(0.0, stock.stockDe(b.id()), 1e-9, "tudo ou nada: a entrada válida também não ficou");
    }

    @Test
    void simularNaoGravaNada() throws SQLException {
        Artigo a = novoArtigo("A1", 0);
        int antes = contar("SELECT COUNT(*) FROM movimento_stock");

        ResultadoImportacao r = imp.importarMovimentos(folha(CAB_MOV,
                l("A1", LocalDate.of(2026, 10, 1), "Entrada", 5.0, null)), true);

        assertFalse(r.aplicada());
        assertEquals(1, r.criados());
        assertTrue(r.temAlteracoes());
        assertEquals(antes, contar("SELECT COUNT(*) FROM movimento_stock"));
        assertEquals(0.0, stock.stockDe(a.id()), 1e-9);
    }

    @Test
    void movimentosComErrosPorLinha() throws SQLException {
        novoArtigo("A1", 10);

        ResultadoImportacao r = imp.importarMovimentos(folha(CAB_MOV,
                l("ZZZ", LocalDate.of(2026, 10, 1), "Entrada", 1.0, null),
                l("A1", null, "Entrada", 1.0, null),
                l("A1", LocalDate.of(2026, 10, 1), "Devolução", 1.0, null),
                l("A1", LocalDate.of(2026, 10, 1), "Entrada", 0.0, null),
                l("A1", LocalDate.of(2026, 10, 1), "Entrada", null, null),
                l("A1", HOJE.plusDays(1), "Entrada", 1.0, null)), false);

        assertFalse(r.aplicada());
        List<String> m = mensagens(r);
        assertEquals(6, m.size(), m.toString());
        assertTrue(m.get(0).startsWith("2: O artigo «ZZZ» não existe"), m.get(0));
        assertEquals("3: A data é obrigatória.", m.get(1));
        assertTrue(m.get(2).startsWith("4: O tipo «Devolução» não é válido (use: Entrada ou Saída)"), m.get(2));
        assertEquals("5: A quantidade tem de ser superior a zero.", m.get(3));
        assertEquals("6: A quantidade é obrigatória.", m.get(4));
        assertEquals("7: A data não pode ser futura.", m.get(5));
    }

    @Test
    void movimentosExigemAsColunasObrigatorias() throws SQLException {
        novoArtigo("A1", 0);

        ResultadoImportacao r = imp.importarMovimentos(folha(List.of("Código", "Data", "Nota"),
                l("A1", LocalDate.of(2026, 10, 1), "x")), false);

        assertFalse(r.aplicada());
        assertEquals(List.of("0: Falta a coluna obrigatória «Tipo».", "0: Falta a coluna obrigatória «Quantidade»."),
                mensagens(r));
    }

    @Test
    void movimentosAvisamDeLinhasQueJaEstaoRegistadas() throws SQLException {
        Artigo a = novoArtigo("A1", 0);
        LocalDate dia = LocalDate.of(2026, 10, 1);
        stock.registar(a.id(), TipoMovimento.ENTRADA, dia, 5, "Fatura 1");

        ResultadoImportacao r = imp.importarMovimentos(folha(CAB_MOV,
                l("A1", dia, "Entrada", 5.0, "Fatura 1"),
                l("A1", dia, "Entrada", 5.0, "Fatura 2")), true);

        assertEquals(2, r.criados());
        assertEquals(1, r.avisos().size());
        assertTrue(r.avisos().get(0).startsWith("1 linha coincide com movimentos já registados"), r.avisos().get(0));
    }

    @Test
    void movimentosIgnoramColunasExtraEReconhecemOCabecalhoExportado() throws SQLException {
        Artigo a = novoArtigo("A1", 0);

        // os títulos que a exportação escreve
        ResultadoImportacao r = imp.importarMovimentos(folha(
                List.of("Data", "Código", "Descrição", "Tipo", "Quantidade", "Unidade", "Nota"),
                l(LocalDate.of(2026, 10, 1), "A1", "Artigo A1", "Entrada", 3.0, "un", null)), false);

        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(List.of("Descrição", "Unidade"), r.colunasIgnoradas());
        assertEquals(3.0, stock.stockDe(a.id()), 1e-9);
    }

    // ---- intervenções ------------------------------------------------------

    @Test
    void registaIntervencoesELigaAoPlanoDoTipoDaMaquina() throws SQLException {
        Maquina t1 = novaMaquina("T1");
        PlanoManutencao oleo = plano(t1, "Mudar óleo", 6);

        ResultadoImportacao r = imp.importarIntervencoes(folha(CAB_INT,
                l("t1", LocalDate.of(2026, 3, 1), "Preventiva", "mudar oleo", "Óleo novo", 35.5),
                l("T1", LocalDate.of(2026, 4, 1), "Corretiva", null, "Fuga", null)), false);

        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(2, r.criados());
        List<Intervencao> todas = manutencao.intervencoes();
        assertEquals(2, todas.size());
        Intervencao preventiva = todas.stream().filter(i -> i.tipo() == TipoIntervencao.PREVENTIVA).findFirst().orElseThrow();
        assertEquals(oleo.id(), preventiva.planoId());
        assertEquals(35.5, preventiva.custo());
        assertEquals("Óleo novo", preventiva.descricao());
        Intervencao corretiva = todas.stream().filter(i -> i.tipo() == TipoIntervencao.CORRETIVA).findFirst().orElseThrow();
        assertNull(corretiva.planoId());
        assertNull(corretiva.custo());
        // a próxima data do plano conta a partir da intervenção importada
        assertEquals(LocalDate.of(2026, 9, 1), manutencao.planosDasMaquinas().get(0).prazo().proxima());
    }

    @Test
    void intervencaoNaoMexeNoStock() throws SQLException {
        Maquina t1 = novaMaquina("T1");
        Artigo a = novoArtigo("A1", 5);
        int movimentosAntes = contar("SELECT COUNT(*) FROM movimento_stock");

        imp.importarIntervencoes(folha(CAB_INT,
                l("T1", LocalDate.of(2026, 3, 1), "Corretiva", null, "Reparação", 10.0)), false);

        assertEquals(movimentosAntes, contar("SELECT COUNT(*) FROM movimento_stock"));
        assertEquals(5.0, stock.stockDe(a.id()), 1e-9);
    }

    @Test
    void intervencaoSoUsaOsPlanosDoTipoDaMaquina() throws SQLException {
        Maquina torno = novaMaquina("T1", "Tornos");
        Maquina fresa = novaMaquina("F1", "Fresadoras");
        plano(torno, "Mudar óleo", 6);

        ResultadoImportacao r = imp.importarIntervencoes(folha(CAB_INT,
                l("F1", LocalDate.of(2026, 3, 1), "Preventiva", "Mudar óleo", null, null)), false);

        assertFalse(r.aplicada());
        assertEquals(1, r.erros().size());
        String msg = r.erros().get(0).mensagem();
        assertTrue(msg.contains("O tipo «Fresadoras» da máquina F1 não tem o plano «Mudar óleo»"), msg);
        assertEquals(0, contar("SELECT COUNT(*) FROM intervencao"));
        assertNotEquals(fresa.categoriaId(), torno.categoriaId());
    }

    @Test
    void intervencaoCorretivaNaoPodeTerPlano() throws SQLException {
        Maquina t1 = novaMaquina("T1");
        plano(t1, "Mudar óleo", 6);

        ResultadoImportacao r = imp.importarIntervencoes(folha(CAB_INT,
                l("T1", LocalDate.of(2026, 3, 1), "Corretiva", "Mudar óleo", null, null)), false);

        assertFalse(r.aplicada());
        assertEquals("2: Só uma intervenção preventiva ou programada pode ser ligada a um plano.", mensagens(r).get(0));
    }

    @Test
    void intervencoesComErrosPorLinhaENaoGravamNada() throws SQLException {
        novaMaquina("T1");

        ResultadoImportacao r = imp.importarIntervencoes(folha(CAB_INT,
                l("T1", LocalDate.of(2026, 3, 1), "Corretiva", null, "Boa", null),
                l("XX", LocalDate.of(2026, 3, 1), "Corretiva", null, null, null),
                l("T1", null, "Corretiva", null, null, null),
                l("T1", LocalDate.of(2026, 3, 1), "Revisão", null, null, null),
                l("T1", HOJE.plusDays(3), "Corretiva", null, null, null),
                l("T1", LocalDate.of(2026, 3, 1), "Corretiva", null, null, "abc")), false);

        assertFalse(r.aplicada());
        List<String> m = mensagens(r);
        assertEquals(5, m.size(), m.toString());
        assertTrue(m.get(0).startsWith("3: A máquina «XX» não existe"), m.get(0));
        assertEquals("4: A data é obrigatória.", m.get(1));
        assertTrue(m.get(2).startsWith("5: O tipo «Revisão» não é válido (use: Preventiva, Programada ou Corretiva)"), m.get(2));
        assertEquals("6: A data não pode ser futura.", m.get(3));
        assertTrue(m.get(4).startsWith("7: O custo"), m.get(4));
        assertEquals(0, contar("SELECT COUNT(*) FROM intervencao"), "a 1.ª linha era válida mas nada fica");
    }

    @Test
    void intervencoesAvisamDeLinhasQueJaEstaoRegistadas() throws SQLException {
        Maquina t1 = novaMaquina("T1");
        LocalDate dia = LocalDate.of(2026, 3, 1);
        manutencao.registarIntervencao(new Intervencao(null, t1.id(), null, dia, TipoIntervencao.CORRETIVA, "Fuga", 12.0),
                List.<ConsumoMaterial>of());

        ResultadoImportacao r = imp.importarIntervencoes(folha(CAB_INT,
                l("T1", dia, "Corretiva", null, "Fuga", 12.0),
                l("T1", dia, "Corretiva", null, "Outra", 12.0)), true);

        assertEquals(2, r.criados());
        assertEquals(1, r.avisos().size());
        assertTrue(r.avisos().get(0).startsWith("1 linha coincide com intervenções já registadas"), r.avisos().get(0));
    }

    @Test
    void intervencaoDeMaquinaAbatidaPodeSerImportada() throws SQLException {
        Maquina t1 = novaMaquina("T1");
        estados.mudarEstado(t1.id(), EstadoMaquina.ABATIDO, HOJE.minusDays(1), "Fim de vida");

        ResultadoImportacao r = imp.importarIntervencoes(folha(CAB_INT,
                l("T1", LocalDate.of(2025, 1, 10), "Corretiva", null, "Histórico antigo", null)), false);

        assertTrue(r.aplicada(), mensagens(r).toString());
    }

    // ---- estados -----------------------------------------------------------

    @Test
    void mudaOEstadoDeMaquinasExistentesComHistorico() throws SQLException {
        Maquina t1 = novaMaquina("T1");
        Maquina t2 = novaMaquina("T2");

        ResultadoImportacao r = imp.importarEstados(folha(CAB_EST,
                l("t1", "Inoperativo", LocalDate.of(2026, 9, 20), "Avaria do fuso"),
                l("T2", "manutenção", null, null)), false);

        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(2, r.criados());
        assertEquals(EstadoMaquina.INOPERATIVO, inv.maquinas().stream().filter(m -> m.id().equals(t1.id())).findFirst().orElseThrow().estado());
        var h1 = estados.historico(t1.id()).get(0);
        assertEquals(LocalDate.of(2026, 9, 20), h1.dataEstado());
        assertEquals("Avaria do fuso", h1.motivo());
        var h2 = estados.historico(t2.id()).get(0);
        assertEquals(HOJE, h2.dataEstado(), "sem data é hoje");
        assertEquals("Importação do Excel", h2.motivo());
    }

    @Test
    void maquinaJaNoEstadoIndicadoContaComoSemAlteracoes() throws SQLException {
        Maquina t1 = novaMaquina("T1");
        int historicoAntes = estados.historico(t1.id()).size();

        ResultadoImportacao r = imp.importarEstados(folha(CAB_EST, l("T1", "Ativo", null, null)), false);

        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(1, r.semAlteracoes());
        assertEquals(0, r.criados());
        assertFalse(r.temAlteracoes());
        assertEquals(historicoAntes, estados.historico(t1.id()).size());
    }

    @Test
    void linhasDaMesmaMaquinaAplicamSeEmSequencia() throws SQLException {
        Maquina t1 = novaMaquina("T1");

        ResultadoImportacao r = imp.importarEstados(folha(CAB_EST,
                l("T1", "Inoperativo", LocalDate.of(2026, 9, 1), "Avaria"),
                l("T1", "Manutenção", LocalDate.of(2026, 9, 5), "Peça pedida"),
                l("T1", "Ativo", LocalDate.of(2026, 9, 9), "Reparada"),
                l("T1", "Ativo", LocalDate.of(2026, 9, 10), "Repetida")), false);

        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(3, r.criados());
        assertEquals(1, r.semAlteracoes(), "a última já estava Ativo depois da anterior");
        assertEquals(EstadoMaquina.ATIVO, inv.maquinas().get(0).estado());
        assertEquals(4, estados.historico(t1.id()).size(), "o registo inicial e as 3 mudanças");
    }

    @Test
    void estadosComErrosNaoGravamNada() throws SQLException {
        Maquina t1 = novaMaquina("T1");
        novaMaquina("T2");
        estados.mudarEstado(t1.id(), EstadoMaquina.INOPERATIVO, HOJE.minusDays(2), "Avaria");

        ResultadoImportacao r = imp.importarEstados(folha(CAB_EST,
                l("T2", "Abatido", null, null),
                l("XX", "Ativo", null, null),
                l("T1", "Parada", null, null),
                l("T1", "Ativo", LocalDate.of(2026, 1, 1), "Antes da última mudança"),
                l("T1", "Ativo", HOJE.plusDays(1), "Futuro")), false);

        assertFalse(r.aplicada());
        List<String> m = mensagens(r);
        assertEquals(4, m.size(), m.toString());
        assertTrue(m.get(0).startsWith("3: A máquina «XX» não existe"), m.get(0));
        assertTrue(m.get(1).startsWith("4: O estado «Parada» não é válido"), m.get(1));
        assertTrue(m.get(2).startsWith("5: A data não pode ser anterior à última mudança de estado"), m.get(2));
        assertEquals("6: A data não pode ser futura.", m.get(3));
        assertEquals(EstadoMaquina.ATIVO, inv.maquinas().stream().filter(x -> x.codigo().equals("T2")).findFirst().orElseThrow().estado());
        assertEquals(1, estados.historico(inv.maquinas().stream().filter(x -> x.codigo().equals("T2")).findFirst().orElseThrow().id()).size());
    }

    @Test
    void estadosReconhecemOCabecalhoExportadoEIgnoramAsColunasExtra() throws SQLException {
        novaMaquina("T1");

        // os títulos que a exportação escreve: "Desde" não é uma data de mudança, ignora-se
        ResultadoImportacao r = imp.importarEstados(folha(
                List.of("Código", "Descrição", "Categoria", "Localização", "Estado", "Desde"),
                l("T1", "Máquina T1", "Tornos", null, "Abatido", LocalDate.of(2020, 1, 1))), false);

        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(List.of("Descrição", "Categoria", "Localização", "Desde"), r.colunasIgnoradas());
        assertEquals(EstadoMaquina.ABATIDO, inv.maquinas().get(0).estado());
        assertEquals(HOJE, estados.historico(inv.maquinas().get(0).id()).get(0).dataEstado());
    }

    @Test
    void estadosExigemAsColunasObrigatorias() throws SQLException {
        novaMaquina("T1");

        ResultadoImportacao r = imp.importarEstados(folha(List.of("Código", "Motivo"), l("T1", "x")), false);

        assertFalse(r.aplicada());
        assertEquals(List.of("0: Falta a coluna obrigatória «Estado»."), mensagens(r));
    }

    // ---- as importações de inventário continuam a pedir o que pediam --------

    @Test
    void importarArtigosContinuaAExigirDescricaoECategoria() throws SQLException {
        ResultadoImportacao r = imp.importarArtigos(folha(List.of("Código", "Quantidade"), l("A1", 3.0)), false);

        assertFalse(r.aplicada());
        assertEquals(List.of("0: Falta a coluna obrigatória «Descrição».", "0: Falta a coluna obrigatória «Categoria»."),
                mensagens(r));
    }
}
