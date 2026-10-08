package pt.oficina.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SchemaTest {

    private Database db;
    private Connection c;

    @BeforeEach
    void abrir() throws SQLException {
        db = Database.inMemory();
        c = db.connection();
        exec("INSERT INTO categoria(nome, tipo) VALUES ('Tornos', 'MAQUINA')");
        exec("INSERT INTO categoria(nome, tipo) VALUES ('Pastilhas', 'ARTIGO')");
        exec("INSERT INTO maquina(codigo, descricao, categoria_id) VALUES ('T01', 'Torno', 1)");
        exec("INSERT INTO artigo(codigo, descricao, categoria_id) VALUES ('A01', 'Pastilha', 2)");
    }

    @AfterEach
    void fechar() throws SQLException {
        db.close();
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String escalar(String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void chavesEstrangeirasAtivas() throws SQLException {
        assertEquals("1", escalar("PRAGMA foreign_keys"));
        assertThrows(SQLException.class,
                () -> exec("INSERT INTO maquina(codigo, descricao, categoria_id) VALUES ('X', 'x', 999)"));
    }

    @Test
    void estadoPorDefeitoEAtivo() throws SQLException {
        assertEquals("ATIVO", escalar("SELECT estado FROM maquina WHERE codigo = 'T01'"));
    }

    @Test
    void valoresFixosValidados() {
        assertThrows(SQLException.class, () -> exec("UPDATE maquina SET estado = 'AVARIADO'"));
        assertThrows(SQLException.class,
                () -> exec("INSERT INTO movimento_stock(artigo_id, data_mov, tipo, quantidade) VALUES (1, '2026-01-01', 'ENTRADA', 0)"));
        assertThrows(SQLException.class,
                () -> exec("INSERT INTO plano_manutencao(categoria_id, tarefa, periodicidade_meses) VALUES (1, 'x', 0)"));
    }

    @Test
    void valoresDaVersao2SaoAceitesEOutrosContinuamRecusados() throws SQLException {
        exec("UPDATE maquina SET estado = 'MANUTENCAO' WHERE codigo = 'T01'");
        exec("INSERT INTO intervencao(maquina_id, data_interv, tipo) VALUES (1, '2026-01-01', 'PROGRAMADA')");
        exec("INSERT INTO categoria(nome, tipo) VALUES ('Brocas','FERRAMENTA'), ('Óleos','CONSUMIVEL'), ('Vários','OUTROS')");
        assertThrows(SQLException.class, () -> exec("UPDATE maquina SET estado = 'EM_REPARACAO'"));
        assertThrows(SQLException.class, () -> exec("INSERT INTO categoria(nome, tipo) VALUES ('x','SERVICO')"));
    }

    @Test
    void apagarRespeitaAsChavesEstrangeiras() throws SQLException {
        exec("INSERT INTO movimento_stock(artigo_id, data_mov, tipo, quantidade) VALUES (1, '2026-01-01', 'ENTRADA', 1)");
        exec("INSERT INTO intervencao(maquina_id, data_interv, tipo) VALUES (1, '2026-01-01', 'CORRETIVA')");
        SQLException artigo = assertThrows(SQLException.class, () -> exec("DELETE FROM artigo")); // tem movimentos
        SQLException maquina = assertThrows(SQLException.class, () -> exec("DELETE FROM maquina")); // tem intervenção
        // O serviço reconhece a restrição por este texto: se o driver o mudar, este teste avisa.
        assertTrue(artigo.getMessage().contains("FOREIGN KEY"), artigo.getMessage());
        assertTrue(maquina.getMessage().contains("FOREIGN KEY"), maquina.getMessage());
        exec("DELETE FROM intervencao");
        exec("DELETE FROM maquina"); // sem registos associados: já se pode apagar
    }

    @Test
    void historicoSoSeAcrescenta() throws SQLException {
        exec("INSERT INTO historico_estado(maquina_id, data_estado, estado, motivo) VALUES (1, '2026-01-01', 'ATIVO', 'Registo inicial')");
        exec("INSERT INTO historico_estado(maquina_id, data_estado, estado, motivo) VALUES (1, '2026-02-01', 'INOPERATIVO', 'Avaria')");
        assertThrows(SQLException.class, () -> exec("UPDATE historico_estado SET motivo = 'x'"));
        assertThrows(SQLException.class, () -> exec("DELETE FROM historico_estado"));
        assertThrows(SQLException.class, () -> exec("DELETE FROM historico_estado WHERE maquina_id = 1"));
    }

    @Test
    void soSePodeApagarOHistoricoDeUmaMaquinaQueNuncaMudouDeEstado() throws SQLException {
        exec("INSERT INTO historico_estado(maquina_id, data_estado, estado, motivo) VALUES (1, '2026-01-01', 'ATIVO', 'Registo inicial')");
        assertThrows(SQLException.class, () -> exec("UPDATE historico_estado SET motivo = 'x'")); // nunca se altera
        exec("DELETE FROM historico_estado WHERE maquina_id = 1"); // única linha: é o registo inicial
        assertEquals("0", escalar("SELECT COUNT(*) FROM historico_estado"));
    }

    @Test
    void intervencaoSoPodeLigarSeAPlanosDoTipoDaMaquina() throws SQLException {
        exec("INSERT INTO categoria(nome, tipo) VALUES ('Fresadoras', 'MAQUINA')"); // id 3
        exec("INSERT INTO maquina(codigo, descricao, categoria_id) VALUES ('T02', 'Outro torno', 1)"); // id 2, tipo Tornos
        exec("INSERT INTO maquina(codigo, descricao, categoria_id) VALUES ('F01', 'Fresa', 3)"); // id 3, tipo Fresadoras
        exec("INSERT INTO plano_manutencao(categoria_id, tarefa, periodicidade_meses) VALUES (1, 'Lubrificar', 3)"); // plano de Tornos
        // máquinas do tipo do plano: podem; de outro tipo: não
        exec("INSERT INTO intervencao(maquina_id, plano_id, data_interv, tipo) VALUES (1, 1, '2026-01-01', 'PREVENTIVA')");
        exec("INSERT INTO intervencao(maquina_id, plano_id, data_interv, tipo) VALUES (2, 1, '2026-01-01', 'PREVENTIVA')");
        SQLException e = assertThrows(SQLException.class, () -> exec(
                "INSERT INTO intervencao(maquina_id, plano_id, data_interv, tipo) VALUES (3, 1, '2026-01-01', 'PREVENTIVA')"));
        assertTrue(e.getMessage().contains("não se aplica") || e.getMessage().contains("nao se aplica"), e.getMessage());
        // também ao alterar uma intervenção existente
        exec("INSERT INTO intervencao(maquina_id, data_interv, tipo) VALUES (3, '2026-01-01', 'CORRETIVA')");
        assertThrows(SQLException.class, () -> exec("UPDATE intervencao SET plano_id = 1 WHERE maquina_id = 3"));
    }

    @Test
    void tarefaUnicaPorTipoSemDistinguirMaiusculasEPlanoSoDeTipoExistente() throws SQLException {
        exec("INSERT INTO plano_manutencao(categoria_id, tarefa, periodicidade_meses) VALUES (1, 'Lubrificar', 3)");
        assertThrows(SQLException.class, () -> exec("INSERT INTO plano_manutencao(categoria_id, tarefa, periodicidade_meses) VALUES (1, 'Lubrificar', 6)"));
        assertThrows(SQLException.class, () -> exec("INSERT INTO plano_manutencao(categoria_id, tarefa, periodicidade_meses) VALUES (1, 'LUBRIFICAR', 6)"));
        exec("INSERT INTO categoria(nome, tipo) VALUES ('Fresadoras', 'MAQUINA')");
        exec("INSERT INTO plano_manutencao(categoria_id, tarefa, periodicidade_meses) VALUES (3, 'Lubrificar', 6)"); // outro tipo: pode
        assertThrows(SQLException.class, () -> exec("INSERT INTO plano_manutencao(categoria_id, tarefa, periodicidade_meses) VALUES (999, 'x', 1)"));
    }

    @Test
    void esquemaAplicadoUmaSoVez() throws SQLException {
        assertEquals(String.valueOf(SchemaInitializer.VERSAO_ATUAL), escalar("PRAGMA user_version"));
        SchemaInitializer.apply(c); // não deve falhar nem recriar tabelas
        assertEquals("T01", escalar("SELECT codigo FROM maquina"));
    }
}
