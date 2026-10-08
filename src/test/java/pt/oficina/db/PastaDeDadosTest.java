package pt.oficina.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Onde ficam os dados do utilizador e a cópia única da base que ficava em data/oficina.db. */
class PastaDeDadosTest {

    private static final Path HOME = Path.of("/home/ana");

    @TempDir
    Path tmp;

    // ---- pasta por sistema -----------------------------------------------

    @Test
    void linuxUsaXdgOuLocalShare() {
        assertEquals(HOME.resolve(".local/share/Oficina"), PastaDeDados.pasta("Linux", Map.of(), HOME));
        assertEquals(Path.of("/dados/xdg/Oficina"), PastaDeDados.pasta("Linux", Map.of("XDG_DATA_HOME", "/dados/xdg"), HOME));
        // XDG relativo ou vazio é inválido pela especificação: ignora-se
        assertEquals(HOME.resolve(".local/share/Oficina"), PastaDeDados.pasta("Linux", Map.of("XDG_DATA_HOME", "relativo"), HOME));
        assertEquals(HOME.resolve(".local/share/Oficina"), PastaDeDados.pasta("Linux", Map.of("XDG_DATA_HOME", " "), HOME));
    }

    @Test
    void windowsUsaAppdata() {
        assertEquals(Path.of("C:/Users/Ana/AppData/Roaming/Oficina"),
                PastaDeDados.pasta("Windows 11", Map.of("APPDATA", "C:/Users/Ana/AppData/Roaming"), HOME));
        assertEquals(HOME.resolve("AppData/Roaming/Oficina"), PastaDeDados.pasta("Windows 10", Map.of(), HOME));
    }

    @Test
    void macUsaApplicationSupport() {
        assertEquals(HOME.resolve("Library/Application Support/Oficina"), PastaDeDados.pasta("Mac OS X", Map.of(), HOME));
    }

    // ---- cópia da base antiga --------------------------------------------

    private Path baseAntigaComDados() throws SQLException, IOException {
        Path antiga = tmp.resolve("projeto/data/oficina.db");
        try (Database db = Database.open(antiga); Statement st = db.connection().createStatement()) {
            st.execute("INSERT INTO categoria(nome, tipo) VALUES ('Tornos','MAQUINA')");
        }
        Files.writeString(antiga.resolveSibling("backups/oficina-20261005-151842.db"), "copia", java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        return antiga;
    }

    private static String escalar(Path ficheiro, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro);
                Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void copiaABaseEAsCopiasDeSegurancaSemTocarNasAntigas() throws Exception {
        Path antigaPasta = tmp.resolve("projeto/data");
        Files.createDirectories(antigaPasta.resolve("backups"));
        Path antiga = baseAntigaComDados();
        long tamanho = Files.size(antiga);
        Path nova = tmp.resolve("utilizador/Oficina/oficina.db");

        assertTrue(PastaDeDados.migrarDaPastaAntiga(antiga, nova));

        assertEquals("Tornos", escalar(nova, "SELECT nome FROM categoria"));
        assertEquals(escalar(antiga, "PRAGMA user_version"), escalar(nova, "PRAGMA user_version"));
        assertEquals("copia", Files.readString(nova.resolveSibling("backups/oficina-20261005-151842.db")));
        assertFalse(Files.exists(nova.resolveSibling("oficina.db.tmp")), "ficheiro temporário por limpar");
        // a antiga fica exatamente como estava
        assertTrue(Files.exists(antiga));
        assertEquals(tamanho, Files.size(antiga));
        assertTrue(Files.exists(antigaPasta.resolve("backups/oficina-20261005-151842.db")));
    }

    @Test
    void soCopiaUmaVezENuncaSobrepoeABaseNova() throws Exception {
        Files.createDirectories(tmp.resolve("projeto/data/backups"));
        Path antiga = baseAntigaComDados();
        Path nova = tmp.resolve("utilizador/Oficina/oficina.db");
        assertTrue(PastaDeDados.migrarDaPastaAntiga(antiga, nova));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + nova); Statement st = c.createStatement()) {
            st.execute("INSERT INTO categoria(nome, tipo) VALUES ('Só na nova','MAQUINA')");
        }
        assertFalse(PastaDeDados.migrarDaPastaAntiga(antiga, nova), "a base nova já existe");
        assertEquals("2", escalar(nova, "SELECT COUNT(*) FROM categoria"));
    }

    @Test
    void semBaseAntigaNaoFazNada() throws Exception {
        Path nova = tmp.resolve("utilizador/Oficina/oficina.db");
        assertFalse(PastaDeDados.migrarDaPastaAntiga(tmp.resolve("nao/existe.db"), nova));
        assertFalse(Files.exists(nova));
    }

    @Test
    void baseAntigaInvalidaFalhaSemDeixarBaseNovaMeioEscrita() throws Exception {
        Path antiga = tmp.resolve("projeto/data/oficina.db");
        Files.createDirectories(antiga.getParent());
        Files.writeString(antiga, "isto não é uma base de dados SQLite, é só texto suficiente para não estar vazio".repeat(5));
        Path nova = tmp.resolve("utilizador/Oficina/oficina.db");

        assertThrows(SQLException.class, () -> PastaDeDados.migrarDaPastaAntiga(antiga, nova));
        assertFalse(Files.exists(nova), "não pode ficar uma base nova que a aplicação tomaria por boa");
        assertFalse(Files.exists(nova.resolveSibling("oficina.db.tmp")));
        assertTrue(Files.exists(antiga));
    }

    @Test
    void aPropriedadeOficinaDbTemPrioridade() {
        String anterior = System.getProperty(Database.PROP_DB);
        try {
            System.setProperty(Database.PROP_DB, tmp.resolve("outra/minha.db").toString());
            assertEquals(tmp.resolve("outra/minha.db"), Database.defaultPath());
        } finally {
            if (anterior == null) {
                System.clearProperty(Database.PROP_DB);
            } else {
                System.setProperty(Database.PROP_DB, anterior);
            }
        }
    }
}
