package pt.oficina.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Cria o esquema na primeira execução (PRAGMA user_version = 0) e migra bases de versões anteriores.
 * Versões: 1 = esquema inicial; 2 = alarga os CHECK (MANUTENCAO, PROGRAMADA, tipos de categoria de artigos);
 * 3 = passa a poder apagar-se máquinas e artigos nunca usados; 4 = planos de manutenção por tipo de máquina;
 * 5 = imagem do artigo; 6 = imagem da máquina.
 */
final class SchemaInitializer {

    static final int VERSAO_ATUAL = 6;
    private static final String SCHEMA = "/db/schema.sql";
    private static final String MIGRACAO_2 = "/db/migracao-2.sql";
    private static final String MIGRACAO_3 = "/db/migracao-3.sql";
    private static final String MIGRACAO_4 = "/db/migracao-4.sql";
    private static final String MIGRACAO_5 = "/db/migracao-5.sql";
    private static final String MIGRACAO_6 = "/db/migracao-6.sql";
    private static final String SEPARADOR = "(?m)^-- @@\\s*$";

    private SchemaInitializer() {
    }

    static void apply(Connection c) throws SQLException {
        int v = versao(c);
        if (v == VERSAO_ATUAL) {
            return;
        }
        if (v > VERSAO_ATUAL) {
            throw new SQLException("A base de dados é da versão " + v + ", mais recente do que esta aplicação ("
                    + VERSAO_ATUAL + "). Atualize a aplicação; a base de dados não foi alterada.");
        }
        if (v == 0) {
            Tx.run(c, () -> {
                executar(c, SCHEMA);
                definirVersao(c, VERSAO_ATUAL);
            });
            return;
        }
        if (v < 2) {
            migrarPara2(c);
        }
        if (v < 3) {
            migrarPara3(c);
        }
        if (v < 4) {
            migrarPara4(c);
        }
        if (v < 5) {
            migrarPara5(c);
        }
        if (v < 6) {
            migrarPara6(c);
        }
    }

    static int versao(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Reconstrói as tabelas afetadas. É preciso desligar as chaves estrangeiras (o PRAGMA não tem efeito dentro
     * de uma transação) e, no fim, verificá-las: se algo estiver inconsistente, a transação é desfeita e a base
     * de dados fica como estava.
     */
    private static void migrarPara2(Connection c) throws SQLException {
        executarSemChavesEstrangeiras(c, () -> Tx.run(c, () -> {
            executar(c, MIGRACAO_2);
            verificarChavesEstrangeiras(c);
            definirVersao(c, 2);
        }));
    }

    /** Reconstrói a tabela dos planos (por máquina -> por tipo de máquina); precisa das chaves estrangeiras desligadas. */
    private static void migrarPara4(Connection c) throws SQLException {
        executarSemChavesEstrangeiras(c, () -> Tx.run(c, () -> {
            executar(c, MIGRACAO_4);
            verificarChavesEstrangeiras(c);
            definirVersao(c, 4);
        }));
    }

    /** Só acrescenta uma coluna: não reconstrói tabelas, por isso não precisa de desligar as chaves estrangeiras. */
    private static void migrarPara5(Connection c) throws SQLException {
        Tx.run(c, () -> {
            executar(c, MIGRACAO_5);
            definirVersao(c, 5);
        });
    }

    /** Como a 5, mas para a máquina: só acrescenta uma coluna. */
    private static void migrarPara6(Connection c) throws SQLException {
        Tx.run(c, () -> {
            executar(c, MIGRACAO_6);
            definirVersao(c, 6);
        });
    }

    /** Só troca triggers: não reconstrói tabelas, por isso não precisa de desligar as chaves estrangeiras. */
    private static void migrarPara3(Connection c) throws SQLException {
        Tx.run(c, () -> {
            executar(c, MIGRACAO_3);
            definirVersao(c, 3);
        });
    }

    private static void executarSemChavesEstrangeiras(Connection c, Tx.VoidWork trabalho) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = OFF");
        }
        Throwable falha = null;
        try {
            trabalho.run();
        } catch (Throwable t) {
            falha = t;
            throw t;
        } finally {
            try {
                if (!c.isClosed()) {
                    try (Statement st = c.createStatement()) {
                        st.execute("PRAGMA foreign_keys = ON");
                    }
                }
            } catch (SQLException e) {
                if (falha == null) {
                    throw e;
                }
                falha.addSuppressed(e);
            }
        }
    }

    private static void verificarChavesEstrangeiras(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
            if (rs.next()) {
                throw new SQLException("Migração desfeita: há registos com referências inválidas na tabela "
                        + rs.getString("table") + " (rowid " + rs.getLong("rowid") + ").");
            }
        }
    }

    private static void definirVersao(Connection c, int versao) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA user_version = " + versao);
        }
    }

    private static void executar(Connection c, String recurso) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String bloco : ler(recurso).split(SEPARADOR)) {
                if (!bloco.isBlank()) {
                    st.execute(bloco);
                }
            }
        }
    }

    private static String ler(String recurso) {
        try (InputStream in = SchemaInitializer.class.getResourceAsStream(recurso)) {
            if (in == null) {
                throw new IllegalStateException("Recurso em falta: " + recurso);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Erro a ler " + recurso, e);
        }
    }
}
