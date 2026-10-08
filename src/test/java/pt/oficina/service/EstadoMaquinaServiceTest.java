package pt.oficina.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.oficina.db.Database;
import pt.oficina.model.HistoricoEstado;
import pt.oficina.model.Maquina;
import pt.oficina.model.PlanoManutencao;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.model.enums.TipoCategoria;

class EstadoMaquinaServiceTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 4);

    private Database db;
    private InventarioService inv;
    private EstadoMaquinaService estados;
    private ManutencaoService man;
    private Maquina torno;

    @BeforeEach
    void preparar() throws SQLException {
        db = Database.inMemory();
        // A máquina é registada 30 dias antes de HOJE; "hoje" nos testes de estado é HOJE + 10.
        inv = new InventarioService(db.connection(), Relogios.em(HOJE.minusDays(30)));
        estados = new EstadoMaquinaService(db.connection(), Relogios.em(HOJE.plusDays(10)));
        man = new ManutencaoService(db.connection(), new StockService(db.connection()), Relogios.em(HOJE));
        long cat = inv.criarCategoria("Tornos", TipoCategoria.MAQUINA).id();
        torno = inv.guardarMaquina(new Maquina(null, "T01", "Torno", null, cat, null, null, null, null));
    }

    @AfterEach
    void fechar() throws SQLException {
        db.close();
    }

    private EstadoMaquina estadoAtual() throws SQLException {
        return inv.maquinas().stream().filter(m -> m.id().equals(torno.id())).findFirst().orElseThrow().estado();
    }

    @Test
    void mudancaAtualizaMaquinaEAcrescentaHistorico() throws SQLException {
        HistoricoEstado h = estados.mudarEstado(torno.id(), EstadoMaquina.INOPERATIVO, HOJE, "  Fuso avariado ");
        assertEquals(EstadoMaquina.INOPERATIVO, estadoAtual());
        assertEquals("Fuso avariado", h.motivo());

        List<HistoricoEstado> lista = estados.historico(torno.id());
        assertEquals(2, lista.size()); // registo inicial + mudança
        assertEquals(EstadoMaquina.INOPERATIVO, lista.get(0).estado());
        assertEquals(EstadoMaquina.ATIVO, lista.get(1).estado());
        assertEquals("Registo inicial", lista.get(1).motivo());
    }

    @Test
    void historicoOrdenadoDoMaisRecenteEDesempataPorId() throws SQLException {
        estados.mudarEstado(torno.id(), EstadoMaquina.INOPERATIVO, HOJE, "Avaria");
        estados.mudarEstado(torno.id(), EstadoMaquina.ATIVO, HOJE, "Reparado"); // mesmo dia
        estados.mudarEstado(torno.id(), EstadoMaquina.ABATIDO, HOJE.plusDays(1), "Fim de vida");
        assertEquals(List.of(EstadoMaquina.ABATIDO, EstadoMaquina.ATIVO, EstadoMaquina.INOPERATIVO, EstadoMaquina.ATIVO),
                estados.historico(torno.id()).stream().map(HistoricoEstado::estado).toList());
        assertEquals(HOJE.plusDays(1), estados.desde().get(torno.id()));
    }

    @Test
    void validacoes() throws SQLException {
        assertThrows(ValidacaoException.class, () -> estados.mudarEstado(torno.id(), null, HOJE, "x"));
        assertThrows(ValidacaoException.class,
                () -> estados.mudarEstado(torno.id(), EstadoMaquina.INOPERATIVO, null, "x"));
        assertThrows(ValidacaoException.class,
                () -> estados.mudarEstado(torno.id(), EstadoMaquina.INOPERATIVO, HOJE, "  "));
        assertThrows(ValidacaoException.class,
                () -> estados.mudarEstado(torno.id(), EstadoMaquina.INOPERATIVO, HOJE, null));
        assertThrows(ValidacaoException.class, () -> estados.mudarEstado(999, EstadoMaquina.ABATIDO, HOJE, "x"));
        // estado igual ao atual
        assertThrows(ValidacaoException.class, () -> estados.mudarEstado(torno.id(), EstadoMaquina.ATIVO, HOJE, "x"));
        // nada disto deixou rasto
        assertEquals(EstadoMaquina.ATIVO, estadoAtual());
        assertEquals(1, estados.historico(torno.id()).size());
    }

    @Test
    void maquinaEmManutencaoSaiDoItinerarioEVoltaAoSerAtivada() throws SQLException {
        man.guardarPlano(new PlanoManutencao(null, torno.categoriaId(), "Lubrificar", 3));
        assertEquals(1, man.itinerario(HOJE).size());

        estados.mudarEstado(torno.id(), EstadoMaquina.MANUTENCAO, HOJE, "Revisão geral");
        assertEquals(EstadoMaquina.MANUTENCAO, estadoAtual());
        assertTrue(man.itinerario(HOJE).isEmpty(), "só máquinas ATIVO entram no itinerário");
        assertEquals(1, man.maquinasParaManutencao().size(), "mas continua a poder receber intervenções");

        estados.mudarEstado(torno.id(), EstadoMaquina.ATIVO, HOJE, "Revisão concluída");
        assertEquals(1, man.itinerario(HOJE).size());
        assertEquals(List.of(EstadoMaquina.ATIVO, EstadoMaquina.MANUTENCAO, EstadoMaquina.ATIVO),
                estados.historico(torno.id()).stream().map(HistoricoEstado::estado).toList());
    }

    @Test
    void dataNaoPodeSerFutura() throws SQLException {
        ValidacaoException e = assertThrows(ValidacaoException.class,
                () -> estados.mudarEstado(torno.id(), EstadoMaquina.INOPERATIVO, HOJE.plusDays(11), "Avaria"));
        assertEquals("A data não pode ser futura.", e.getMessage());
        estados.mudarEstado(torno.id(), EstadoMaquina.INOPERATIVO, HOJE.plusDays(10), "Avaria"); // hoje é aceite
        assertEquals(EstadoMaquina.INOPERATIVO, estadoAtual());
    }

    @Test
    void dataNaoPodeSerAnteriorAUltimaMudanca() throws SQLException {
        estados.mudarEstado(torno.id(), EstadoMaquina.INOPERATIVO, HOJE, "Avaria");
        // Sem esta regra, o estado atual seria ATIVO mas o histórico (por data) acabaria em INOPERATIVO.
        ValidacaoException e = assertThrows(ValidacaoException.class,
                () -> estados.mudarEstado(torno.id(), EstadoMaquina.ATIVO, HOJE.minusDays(1), "Reparado"));
        assertTrue(e.getMessage().contains("anterior à última mudança"), e.getMessage());
        assertTrue(e.getMessage().contains("04/10/2026"), e.getMessage());
        assertEquals(EstadoMaquina.INOPERATIVO, estadoAtual());
        assertEquals(2, estados.historico(torno.id()).size());

        estados.mudarEstado(torno.id(), EstadoMaquina.ATIVO, HOJE, "Reparado"); // mesmo dia é aceite
        assertEquals(EstadoMaquina.ATIVO, estadoAtual());
    }

    @Test
    void historicoContinuaProtegidoContraAlteracoes() throws SQLException {
        estados.mudarEstado(torno.id(), EstadoMaquina.INOPERATIVO, HOJE, "Avaria");
        try (Statement st = db.connection().createStatement()) {
            assertThrows(SQLException.class, () -> st.execute("UPDATE historico_estado SET motivo = 'x'"));
            assertThrows(SQLException.class, () -> st.execute("DELETE FROM historico_estado"));
        }
        assertEquals(2, estados.historico(torno.id()).size());
    }

    @Test
    void maquinaSaiDoItinerarioQuandoDeixaDeEstarAtivo() throws SQLException {
        man.guardarPlano(new PlanoManutencao(null, torno.categoriaId(), "Lubrificar", 3));
        assertEquals(1, man.itinerario(HOJE).size());

        estados.mudarEstado(torno.id(), EstadoMaquina.INOPERATIVO, HOJE, "Avaria");
        assertTrue(man.itinerario(HOJE).isEmpty());

        estados.mudarEstado(torno.id(), EstadoMaquina.ATIVO, HOJE, "Reparado");
        assertEquals(1, man.itinerario(HOJE).size());

        estados.mudarEstado(torno.id(), EstadoMaquina.ABATIDO, HOJE, "Fim de vida");
        assertTrue(man.itinerario(HOJE).isEmpty());
        assertEquals(1, man.planosDeManutencao().size()); // o plano do tipo mantém-se
    }
}
