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

class TxTest {

    private Database db;
    private Connection c;

    @BeforeEach
    void abrir() throws SQLException {
        db = Database.inMemory();
        c = db.connection();
        exec("CREATE TABLE rascunho (x INTEGER)");
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

    private int linhas() throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM rascunho")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void commitQuandoCorreBem() throws SQLException {
        int r = Tx.call(c, () -> {
            exec("INSERT INTO rascunho VALUES (1)");
            return 42;
        });
        assertEquals(42, r);
        assertEquals(1, linhas());
        assertTrue(c.getAutoCommit());
    }

    @Test
    void rollbackEmSqlExceptionRuntimeExceptionEError() throws SQLException {
        assertThrows(SQLException.class, () -> Tx.run(c, () -> {
            exec("INSERT INTO rascunho VALUES (1)");
            throw new SQLException("falha");
        }));
        assertThrows(IllegalStateException.class, () -> Tx.run(c, () -> {
            exec("INSERT INTO rascunho VALUES (2)");
            throw new IllegalStateException("falha");
        }));
        // Um Error não pode deixar meia operação gravada (o setAutoCommit(true) faria COMMIT).
        assertThrows(StackOverflowError.class, () -> Tx.run(c, () -> {
            exec("INSERT INTO rascunho VALUES (3)");
            throw new StackOverflowError();
        }));
        assertEquals(0, linhas());
        assertTrue(c.getAutoCommit());
    }

    @Test
    void transacaoInternaJuntaSeAExterior() throws SQLException {
        assertThrows(IllegalStateException.class, () -> Tx.run(c, () -> {
            Tx.run(c, () -> exec("INSERT INTO rascunho VALUES (1)")); // não faz commit por si
            exec("INSERT INTO rascunho VALUES (2)");
            throw new IllegalStateException("falha depois da interna");
        }));
        assertEquals(0, linhas());
        assertTrue(c.getAutoCommit());

        Tx.run(c, () -> {
            Tx.run(c, () -> exec("INSERT INTO rascunho VALUES (1)"));
            exec("INSERT INTO rascunho VALUES (2)");
        });
        assertEquals(2, linhas());
    }
}
