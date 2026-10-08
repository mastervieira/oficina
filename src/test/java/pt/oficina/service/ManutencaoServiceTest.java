package pt.oficina.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.oficina.db.Database;
import pt.oficina.model.Artigo;
import pt.oficina.model.ConsumoMaterial;
import pt.oficina.model.Intervencao;
import pt.oficina.model.Maquina;
import pt.oficina.model.PlanoComPrazo;
import pt.oficina.model.PlanoDaMaquina;
import pt.oficina.model.PlanoManutencao;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.model.enums.TipoCategoria;
import pt.oficina.model.enums.TipoIntervencao;
import pt.oficina.model.enums.TipoMovimento;

class ManutencaoServiceTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 4);

    private Database db;
    private InventarioService inv;
    private StockService stock;
    private ManutencaoService man;
    private Maquina torno;
    private Maquina fresa;
    private Artigo oleo;
    private long catTornos;
    private long catFresadoras;
    private long catArtigos;

    @BeforeEach
    void preparar() throws SQLException {
        db = Database.inMemory();
        inv = new InventarioService(db.connection(), Relogios.em(HOJE));
        stock = new StockService(db.connection(), Relogios.em(HOJE));
        man = new ManutencaoService(db.connection(), stock, Relogios.em(HOJE));
        catTornos = inv.criarCategoria("Tornos", TipoCategoria.MAQUINA).id();
        catFresadoras = inv.criarCategoria("Fresadoras", TipoCategoria.MAQUINA).id();
        catArtigos = inv.criarCategoria("Lubrificantes", TipoCategoria.ARTIGO).id();
        torno = inv.guardarMaquina(new Maquina(null, "T01", "Torno", null, catTornos, null, null, null, null));
        fresa = inv.guardarMaquina(new Maquina(null, "F01", "Fresa", null, catFresadoras, null, null, null, null));
        oleo = inv.guardarArtigo(new Artigo(null, "O01", "Óleo", catArtigos, null, null, "l", 0));
        stock.registar(oleo.id(), TipoMovimento.ENTRADA, HOJE.minusDays(100), 10, null);
    }

    @AfterEach
    void fechar() throws SQLException {
        db.close();
    }

    /** Cria um plano para o TIPO da máquina (vale para todas as máquinas desse tipo). */
    private PlanoManutencao plano(Maquina m, String tarefa, int meses) throws SQLException {
        return man.guardarPlano(new PlanoManutencao(null, m.categoriaId(), tarefa, meses));
    }

    private Maquina maquinaDoTipo(long categoria, String codigo) throws SQLException {
        return inv.guardarMaquina(new Maquina(null, codigo, "Máquina " + codigo, null, categoria, null, null, null, null));
    }

    private void preventiva(Maquina m, PlanoManutencao p, LocalDate data) throws SQLException {
        man.registarIntervencao(new Intervencao(null, m.id(), p.id(), data, TipoIntervencao.PREVENTIVA, null, null), List.of());
    }

    private void estado(Maquina m, EstadoMaquina e) throws SQLException {
        try (Statement st = db.connection().createStatement()) {
            st.execute("UPDATE maquina SET estado = '" + e.name() + "' WHERE id = " + m.id());
        }
    }

    private int contar(String sql) throws SQLException {
        try (Statement st = db.connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** A situação do plano NESSA máquina (a próxima data é por máquina). */
    private PlanoComPrazo prazo(Maquina m, PlanoManutencao p) throws SQLException {
        return man.planosDasMaquinas().stream()
                .filter(x -> x.maquina().id().equals(m.id()) && x.prazo().plano().id().equals(p.id()))
                .findFirst().orElseThrow().prazo();
    }

    // ---- próxima data ----------------------------------------------------

    @Test
    void planoSemIntervencaoContaComoVencido() throws SQLException {
        PlanoComPrazo p = prazo(torno, plano(torno, "Lubrificar", 3));
        assertTrue(p.semIntervencao());
        assertNull(p.proxima());
        assertTrue(p.vencido(HOJE));
    }

    @Test
    void proximaDataEUltimaIntervencaoMaisPeriodicidade() throws SQLException {
        PlanoManutencao p = plano(torno, "Lubrificar", 3);
        preventiva(torno, p, LocalDate.of(2026, 1, 15));
        preventiva(torno, p, LocalDate.of(2026, 7, 1)); // a mais recente, mesmo gravada depois
        preventiva(torno, p, LocalDate.of(2026, 3, 1)); // mais antiga, gravada por último
        PlanoComPrazo r = prazo(torno, p);
        assertEquals(LocalDate.of(2026, 7, 1), r.ultima());
        assertEquals(LocalDate.of(2026, 10, 1), r.proxima());
        assertTrue(r.vencido(HOJE)); // 1 de outubro já passou
    }

    @Test
    void mesesDeCalendarioFimDeMes() throws SQLException {
        PlanoManutencao p = plano(torno, "Verificar", 1);
        preventiva(torno, p, LocalDate.of(2026, 1, 31));
        assertEquals(LocalDate.of(2026, 2, 28), prazo(torno, p).proxima());
    }

    @Test
    void dataDeHojeAindaNaoEstaVencida() throws SQLException {
        PlanoManutencao p = plano(torno, "Verificar", 6);
        preventiva(torno, p, HOJE.minusMonths(6));
        PlanoComPrazo r = prazo(torno, p);
        assertEquals(HOJE, r.proxima());
        assertEquals(false, r.vencido(HOJE));
        assertEquals(0, r.diasAte(HOJE));
    }

    @Test
    void intervencaoDeOutroPlanoOuCorretivaNaoAvancaOPlano() throws SQLException {
        PlanoManutencao a = plano(torno, "A", 3);
        PlanoManutencao b = plano(torno, "B", 3);
        preventiva(torno, a, HOJE.minusDays(10));
        man.registarIntervencao(new Intervencao(null, torno.id(), null, HOJE, TipoIntervencao.CORRETIVA, "Avaria", 50.0), List.of());
        assertTrue(prazo(torno, b).semIntervencao());
        assertEquals(HOJE.minusDays(10), prazo(torno, a).ultima());
    }

    // ---- itinerário ------------------------------------------------------

    @Test
    void itinerarioIncluiVencidosEJanelaDe30DiasInclusive() throws SQLException {
        plano(torno, "Sem intervenção", 3);
        PlanoManutencao vencido = plano(torno, "Vencido", 1);
        PlanoManutencao hoje = plano(torno, "Hoje", 1);
        PlanoManutencao dia30 = plano(torno, "Dia 30", 1);
        PlanoManutencao dia31 = plano(torno, "Dia 31", 1);
        preventiva(torno, vencido, HOJE.minusMonths(1).minusDays(5));
        preventiva(torno, hoje, HOJE.minusMonths(1));
        preventiva(torno, dia30, HOJE.plusDays(30).minusMonths(1));
        preventiva(torno, dia31, HOJE.plusDays(31).minusMonths(1));

        List<String> tarefas = man.itinerario(HOJE).stream().map(i -> i.prazo().plano().tarefa()).toList();
        assertEquals(List.of("Sem intervenção", "Vencido", "Hoje", "Dia 30"), tarefas);
    }

    @Test
    void itinerarioSoTemMaquinasAtivo() throws SQLException {
        plano(torno, "Torno", 3);
        plano(fresa, "Fresa", 3);
        estado(fresa, EstadoMaquina.INOPERATIVO);
        List<PlanoDaMaquina> it = man.itinerario(HOJE);
        assertEquals(1, it.size());
        assertEquals("T01", it.get(0).maquina().codigo());

        estado(torno, EstadoMaquina.ABATIDO);
        assertTrue(man.itinerario(HOJE).isEmpty());
        // os planos continuam a existir
        assertEquals(2, man.planosDeManutencao().size());
    }

    @Test
    void itinerarioOrdenadoPorProximaData() throws SQLException {
        PlanoManutencao tardio = plano(torno, "Tardio", 1);
        PlanoManutencao cedo = plano(fresa, "Cedo", 1);
        preventiva(torno, tardio, HOJE.minusMonths(1).plusDays(10)); // próxima: hoje+10
        preventiva(fresa, cedo, HOJE.minusMonths(1).minusDays(10)); // próxima: hoje-10
        assertEquals(List.of("Cedo", "Tardio"),
                man.itinerario(HOJE).stream().map(i -> i.prazo().plano().tarefa()).toList());
    }

    // ---- planos ----------------------------------------------------------

    @Test
    void validacaoDePlanos() throws SQLException {
        assertThrows(ValidacaoException.class, () -> plano(torno, "  ", 3));
        assertThrows(ValidacaoException.class, () -> plano(torno, "x", 0));
        assertThrows(ValidacaoException.class, () -> plano(torno, "x", ManutencaoService.MAX_PERIODICIDADE_MESES + 1));
        ValidacaoException inexistente = assertThrows(ValidacaoException.class,
                () -> man.guardarPlano(new PlanoManutencao(null, 999, "x", 3)));
        assertEquals("O tipo de máquina não existe.", inexistente.getMessage());
        // uma categoria de artigos não é um tipo de máquina
        assertThrows(ValidacaoException.class, () -> man.guardarPlano(new PlanoManutencao(null, catArtigos, "x", 3)));
        assertEquals(0, man.planosDeManutencao().size());
    }

    @Test
    void editarPlanoNaoMudaDeTipo() throws SQLException {
        PlanoManutencao p = plano(torno, "Lubrificar", 3);
        PlanoManutencao e = man.guardarPlano(new PlanoManutencao(p.id(), catFresadoras, " Lubrificar guias ", 6));
        assertEquals(catTornos, e.categoriaId());
        assertEquals("Lubrificar guias", e.tarefa());
        assertEquals(6, e.periodicidadeMeses());
    }

    @Test
    void soSeApagaPlanoSemIntervencoes() throws SQLException {
        PlanoManutencao livre = plano(torno, "Livre", 3);
        PlanoManutencao usado = plano(torno, "Usado", 3);
        preventiva(torno, usado, HOJE);
        man.apagarPlano(livre.id());
        assertThrows(ValidacaoException.class, () -> man.apagarPlano(usado.id()));
        assertEquals(1, man.planosDeManutencao().size());
    }

    // ---- intervenções ----------------------------------------------------

    @Test
    void regrasDeLigacaoAoPlano() throws SQLException {
        PlanoManutencao doTorno = plano(torno, "Lubrificar", 3);
        ValidacaoException outroTipo = assertThrows(ValidacaoException.class, () -> man.registarIntervencao(
                new Intervencao(null, fresa.id(), doTorno.id(), HOJE, TipoIntervencao.PREVENTIVA, null, null), List.of()));
        assertEquals("O plano não se aplica ao tipo desta máquina.", outroTipo.getMessage());
        assertThrows(ValidacaoException.class, () -> man.registarIntervencao(
                new Intervencao(null, torno.id(), doTorno.id(), HOJE, TipoIntervencao.CORRETIVA, null, null), List.of()));
        assertEquals(0, contar("SELECT COUNT(*) FROM intervencao"));
    }

    @Test
    void intervencaoProgramadaCumpreOPlanoComoAPreventiva() throws SQLException {
        PlanoManutencao p = plano(torno, "Revisão anual", 12);
        man.registarIntervencao(new Intervencao(null, torno.id(), p.id(), HOJE.minusMonths(11),
                TipoIntervencao.PROGRAMADA, "Revisão", null), List.of());
        assertEquals(HOJE.minusMonths(11), prazo(torno, p).ultima());
        assertEquals(HOJE.plusMonths(1), prazo(torno, p).proxima());
        // só a corretiva não cumpre planos
        assertThrows(ValidacaoException.class, () -> man.registarIntervencao(
                new Intervencao(null, torno.id(), p.id(), HOJE, TipoIntervencao.CORRETIVA, null, null), List.of()));
        assertTrue(TipoIntervencao.PREVENTIVA.cumprePlano() && TipoIntervencao.PROGRAMADA.cumprePlano());
        assertFalse(TipoIntervencao.CORRETIVA.cumprePlano());
    }

    @Test
    void validacaoDeIntervencao() {
        assertThrows(ValidacaoException.class, () -> man.registarIntervencao(
                new Intervencao(null, torno.id(), null, null, TipoIntervencao.CORRETIVA, null, null), List.of()));
        assertThrows(ValidacaoException.class, () -> man.registarIntervencao(
                new Intervencao(null, torno.id(), null, HOJE, null, null, null), List.of()));
        assertThrows(ValidacaoException.class, () -> man.registarIntervencao(
                new Intervencao(null, torno.id(), null, HOJE, TipoIntervencao.CORRETIVA, null, -1.0), List.of()));
        assertThrows(ValidacaoException.class, () -> man.registarIntervencao(
                new Intervencao(null, 999, null, HOJE, TipoIntervencao.CORRETIVA, null, null), List.of()));
    }

    @Test
    void dataFuturaECustoAbsurdoRejeitados() throws SQLException {
        // Uma preventiva com data futura "cumpriria" o plano e tirá-lo-ia do itinerário.
        PlanoManutencao p = plano(torno, "Lubrificar", 3);
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> man.registarIntervencao(
                new Intervencao(null, torno.id(), p.id(), HOJE.plusDays(1), TipoIntervencao.PREVENTIVA, null, null), List.of()));
        assertEquals("A data não pode ser futura.", e.getMessage());
        assertThrows(ValidacaoException.class, () -> man.registarIntervencao(
                new Intervencao(null, torno.id(), null, HOJE, TipoIntervencao.CORRETIVA, null, 1e300), List.of()));
        assertEquals(0, contar("SELECT COUNT(*) FROM intervencao"));
        assertTrue(prazo(torno, p).semIntervencao());
    }

    // ---- planos por tipo de máquina ---------------------------------------

    @Test
    void todasAsMaquinasDoTipoTemOsPlanosDoTipo() throws SQLException {
        Maquina torno2 = maquinaDoTipo(catTornos, "T02");
        Maquina abatido = maquinaDoTipo(catTornos, "T03");
        estado(abatido, EstadoMaquina.ABATIDO);
        PlanoManutencao lubrificar = plano(torno, "Lubrificar", 3);
        PlanoManutencao filtros = plano(torno2, "Filtros", 6); // criado "a partir" de outra máquina: vale para o tipo
        PlanoManutencao daFresa = plano(fresa, "Afinar", 12);

        var porMaquina = man.planosDasMaquinas().stream().collect(java.util.stream.Collectors.groupingBy(
                x -> x.maquina().codigo(), java.util.TreeMap::new,
                java.util.stream.Collectors.mapping(x -> x.prazo().plano().tarefa(), java.util.stream.Collectors.toList())));
        assertEquals(List.of("F01", "T01", "T02"), List.copyOf(porMaquina.keySet()), "as máquinas abatidas não entram");
        assertEquals(List.of("Filtros", "Lubrificar"), porMaquina.get("T01").stream().sorted().toList());
        assertEquals(List.of("Filtros", "Lubrificar"), porMaquina.get("T02").stream().sorted().toList());
        assertEquals(List.of("Afinar"), porMaquina.get("F01"));
        assertEquals(List.of(filtros, lubrificar), man.planosDoTipo(catTornos), "por tarefa");
        assertEquals(List.of(daFresa), man.planosDoTipo(catFresadoras));
    }

    @Test
    void aProximaDataContaSePorMaquina() throws SQLException {
        Maquina torno2 = maquinaDoTipo(catTornos, "T02");
        PlanoManutencao p = plano(torno, "Lubrificar", 3);
        preventiva(torno, p, HOJE.minusDays(10)); // só o T01 foi lubrificado

        PlanoComPrazo doT01 = prazo(torno, p);
        PlanoComPrazo doT02 = prazo(torno2, p);
        assertEquals(HOJE.minusDays(10), doT01.ultima());
        assertEquals(HOJE.minusDays(10).plusMonths(3), doT01.proxima());
        assertTrue(doT02.semIntervencao() && doT02.vencido(HOJE), "o T02 continua por fazer: conta como vencido");
        // no itinerário: o T02 (sem intervenção) vem primeiro; o T01 só entra se a data estiver na janela de 30 dias
        assertEquals(List.of("T02"), man.itinerario(HOJE).stream().map(i -> i.maquina().codigo()).toList());
        preventiva(torno2, p, HOJE.minusMonths(3).minusDays(1));
        assertEquals(List.of("T02"), man.itinerario(HOJE).stream().map(i -> i.maquina().codigo()).toList());
        assertTrue(prazo(torno2, p).vencido(HOJE));
    }

    @Test
    void tarefaNaoSeRepeteNoMesmoTipoMasPodeRepetirseEmOutro() throws SQLException {
        PlanoManutencao p = plano(torno, "Lubrificação geral", 3);
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> plano(torno, "  lubrificacao GERAL ", 6));
        assertEquals("O tipo «Tornos» já tem um plano com a tarefa «Lubrificação geral».", e.getMessage());
        plano(fresa, "Lubrificação geral", 6); // outro tipo: é independente
        PlanoManutencao outro = plano(torno, "Filtros", 6);
        // renomear para o nome de outro plano do tipo também é recusado; guardar sem mudar o nome não
        assertThrows(ValidacaoException.class, () -> man.guardarPlano(new PlanoManutencao(outro.id(), catTornos, "LUBRIFICAÇÃO GERAL", 6)));
        assertEquals(9, man.guardarPlano(new PlanoManutencao(p.id(), catTornos, "Lubrificação geral", 9)).periodicidadeMeses());
        assertEquals(3, man.planosDeManutencao().size());
    }

    @Test
    void mudarUmaMaquinaDeTipoMudaOsSeusPlanosSemApagarOHistorico() throws SQLException {
        PlanoManutencao deTornos = plano(torno, "Lubrificar", 3);
        PlanoManutencao deFresadoras = plano(fresa, "Afinar", 12);
        preventiva(torno, deTornos, HOJE.minusDays(5));

        inv.guardarMaquina(new Maquina(torno.id(), "T01", "Torno", null, catFresadoras, null, null, null, null));
        List<String> tarefas = man.planosDasMaquinas().stream().filter(x -> x.maquina().id().equals(torno.id()))
                .map(x -> x.prazo().plano().tarefa()).toList();
        assertEquals(List.of("Afinar"), tarefas, "passa a ter os planos do novo tipo");
        assertTrue(prazo(torno, deFresadoras).semIntervencao());
        assertEquals(1, man.intervencoes().size(), "a intervenção antiga mantém-se");
        // e já não se pode ligar a planos do tipo antigo
        assertThrows(ValidacaoException.class, () -> preventiva(torno, deTornos, HOJE));
    }

    @Test
    void consumoDeMaterialGravaSaidaComNota() throws SQLException {
        Intervencao i = man.registarIntervencao(
                new Intervencao(null, torno.id(), null, HOJE, TipoIntervencao.CORRETIVA, "  Fuga de óleo ", 35.5),
                List.of(new ConsumoMaterial(oleo.id(), 2.5)));
        assertEquals("Fuga de óleo", man.intervencoes().get(0).descricao());
        assertEquals(35.5, man.intervencoes().get(0).custo());
        assertEquals(7.5, stock.stockDe(oleo.id()), 1e-9);
        var saida = stock.movimentos(oleo.id()).get(0);
        assertEquals(TipoMovimento.SAIDA, saida.tipo());
        assertEquals(HOJE, saida.dataMov());
        assertTrue(saida.nota().contains("#" + i.id()) && saida.nota().contains("T01"), saida.nota());
    }

    @Test
    void faltaDeStockDesfazTudo() throws SQLException {
        // 1.º consumo cabe (6 de 10), o 2.º já não (6 > 4): nem a intervenção nem a 1.ª saída ficam gravadas.
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> man.registarIntervencao(
                new Intervencao(null, torno.id(), null, HOJE, TipoIntervencao.CORRETIVA, null, null),
                List.of(new ConsumoMaterial(oleo.id(), 6), new ConsumoMaterial(oleo.id(), 6))));
        assertTrue(e.getMessage().contains("Stock insuficiente"));
        assertEquals(0, contar("SELECT COUNT(*) FROM intervencao"));
        assertEquals(1, contar("SELECT COUNT(*) FROM movimento_stock")); // só a entrada inicial
        assertEquals(10, stock.stockDe(oleo.id()), 1e-9);
        // a ligação continua utilizável (autocommit reposto)
        assertTrue(db.connection().getAutoCommit());
        preventiva(torno, plano(torno, "Depois", 3), HOJE);
        assertEquals(1, contar("SELECT COUNT(*) FROM intervencao"));
    }

    @Test
    void maquinasParaManutencaoExcluemAbatidas() throws SQLException {
        estado(fresa, EstadoMaquina.ABATIDO);
        assertEquals(List.of("T01"), man.maquinasParaManutencao().stream().map(Maquina::codigo).toList());
    }

    @Test
    void numaMaquinaAbatidaSoSeAceitamIntervencoesAteAoAbate() throws SQLException {
        // a fresa foi registada hoje: o abate e as intervenções seguintes acontecem nos dias seguintes
        LocalDate depois = HOJE.plusDays(20);
        EstadoMaquinaService estados = new EstadoMaquinaService(db.connection(), Relogios.em(depois));
        StockService stockDepois = new StockService(db.connection(), Relogios.em(depois));
        ManutencaoService man = new ManutencaoService(db.connection(), stockDepois, Relogios.em(depois));
        LocalDate abate = HOJE.plusDays(10);
        estados.mudarEstado(fresa.id(), EstadoMaquina.ABATIDO, abate, "Fim de vida");

        // o histórico anterior (por exemplo, importado) continua a poder registar-se, até ao próprio dia do abate
        man.registarIntervencao(new Intervencao(null, fresa.id(), null, abate.minusDays(30), TipoIntervencao.CORRETIVA,
                "Antes do abate", null), List.of());
        man.registarIntervencao(new Intervencao(null, fresa.id(), null, abate, TipoIntervencao.CORRETIVA,
                "No dia do abate", null), List.of());

        ValidacaoException e = assertThrows(ValidacaoException.class, () -> man.registarIntervencao(
                new Intervencao(null, fresa.id(), null, abate.plusDays(1), TipoIntervencao.CORRETIVA, "Depois", null),
                List.of(new ConsumoMaterial(oleo.id(), 1))));
        assertTrue(e.getMessage().contains("abatida desde"), e.getMessage());
        assertEquals(2, contar("SELECT COUNT(*) FROM intervencao"));
        assertEquals(10.0, stock.stockDe(oleo.id()), 1e-9, "nada foi gasto");
    }
}
