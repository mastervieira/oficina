package pt.oficina.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Transação simples sobre a ligação única. Se já houver uma transação em curso, o trabalho junta-se a ela:
 * só a transação mais exterior faz commit ou rollback.
 */
public final class Tx {

    @FunctionalInterface
    public interface Work<T> {
        T run() throws SQLException;
    }

    @FunctionalInterface
    public interface VoidWork {
        void run() throws SQLException;
    }

    /** Fica verdadeiro se alguma vez foi preciso fechar a ligação por um rollback ter falhado (ver {@link #call}). */
    private static volatile boolean ligacaoFechada;

    private Tx() {
    }

    /**
     * A ligação foi fechada depois de um rollback falhado: nada ficou gravado a meio, mas a aplicação (que usa uma
     * só ligação) já não consegue trabalhar e tem de ser reiniciada.
     */
    public static boolean ligacaoFechada() {
        return ligacaoFechada;
    }

    public static <T> T call(Connection c, Work<T> work) throws SQLException {
        if (!c.getAutoCommit()) {
            return work.run(); // junta-se à transação exterior
        }
        c.setAutoCommit(false);
        try {
            T result = work.run();
            c.commit();
            return result;
        } catch (Throwable t) { // inclui Error: nunca deixar meia operação gravada
            try {
                c.rollback();
            } catch (SQLException rollbackFalhou) {
                t.addSuppressed(rollbackFalhou);
                // Sem rollback fiável, repor o autocommit faria COMMIT do que ficou a meio. Fechar a ligação
                // descarta a transação por gravar.
                ligacaoFechada = true;
                try {
                    c.close();
                } catch (SQLException fecharFalhou) {
                    t.addSuppressed(fecharFalhou);
                }
            }
            throw t;
        } finally {
            if (!c.isClosed()) {
                c.setAutoCommit(true); // aqui já não há nada por gravar (commit ou rollback feitos)
            }
        }
    }

    public static void run(Connection c, VoidWork work) throws SQLException {
        call(c, () -> {
            work.run();
            return null;
        });
    }
}
