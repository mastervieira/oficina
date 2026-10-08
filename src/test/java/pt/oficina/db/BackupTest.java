package pt.oficina.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupTest {

    @TempDir
    Path tmp;

    private Database db;

    @BeforeEach
    void abrir() throws SQLException {
        db = Database.open(tmp.resolve("dados/oficina.db"));
        exec(db, "INSERT INTO localizacao(nome) VALUES ('Armazém')");
    }

    @AfterEach
    void fechar() throws SQLException {
        db.close();
    }

    private static void exec(Database d, String sql) throws SQLException {
        try (Statement st = d.connection().createStatement()) {
            st.execute(sql);
        }
    }

    private static String localizacao(Path ficheiro) throws SQLException {
        try (Database d = Database.open(ficheiro); Statement st = d.connection().createStatement();
                ResultSet rs = st.executeQuery("SELECT nome FROM localizacao")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    @Test
    void copiaTemOsMesmosDadosEOEsquema() throws SQLException {
        Path copia = tmp.resolve("copia.db");
        db.copiarPara(copia);
        assertEquals("Armazém", localizacao(copia));
        assertFalse(Files.exists(tmp.resolve("copia.db.tmp")), "o temporário não pode ficar para trás");
    }

    @Test
    void copiaSubstituiDestinoExistenteMasNuncaAPropriaBd() throws SQLException {
        Path copia = tmp.resolve("copia.db");
        db.copiarPara(copia);
        exec(db, "INSERT INTO localizacao(nome) VALUES ('Oficina')");
        db.copiarPara(copia); // substitui a anterior
        try (Database d = Database.open(copia); Statement st = d.connection().createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM localizacao")) {
            rs.next();
            assertEquals(2, rs.getInt(1));
        }
        assertThrows(SQLException.class, () -> db.copiarPara(db.ficheiro()));
        assertEquals("Armazém", localizacao(db.ficheiro())); // a BD original continua intacta e legível
    }

    @Test
    void bdEmMemoriaNaoSeCopia() throws SQLException {
        try (Database mem = Database.inMemory()) {
            assertThrows(IllegalStateException.class, () -> mem.copiarPara(tmp.resolve("x.db")));
        }
    }

    @Test
    void automaticaFazUmaPorDiaEMantemAsMaisRecentes() throws SQLException, IOException {
        LocalDateTime d1 = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
        Optional<Path> a = Backups.automatica(db, d1, 3);
        assertTrue(a.isPresent());
        assertEquals("oficina-20261001-090000.db", a.get().getFileName().toString());
        assertEquals("Armazém", localizacao(a.get()));
        assertTrue(Backups.automatica(db, d1.plusHours(5), 3).isEmpty(), "só uma por dia");

        Backups.automatica(db, d1.plusDays(1), 3);
        Backups.automatica(db, d1.plusDays(2), 3);
        Backups.automatica(db, d1.plusDays(3), 3);
        List<String> nomes = Backups.listar(db).stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of("oficina-20261002-090000.db", "oficina-20261003-090000.db", "oficina-20261004-090000.db"),
                nomes);
    }

    @Test
    void automaticaSoApagaFicheirosComONomeDasCopias() throws SQLException, IOException {
        Path pasta = Backups.pasta(db);
        Files.createDirectories(pasta);
        Path alheio = Files.writeString(pasta.resolve("notas-importantes.txt"), "não apagar");
        Path outroDb = Files.writeString(pasta.resolve("outra-base.db"), "não apagar");
        LocalDateTime d = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
        for (int i = 0; i < 4; i++) {
            Backups.automatica(db, d.plusDays(i), 1);
        }
        assertTrue(Files.exists(alheio));
        assertTrue(Files.exists(outroDb));
        assertEquals(1, Backups.listar(db).size());
    }

    @Test
    void cadaBaseTemASuaPastaEOSeuPrefixoSemTrocarCopiasComAsOutras() throws SQLException, IOException {
        LocalDateTime dia1 = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
        try (Database outra = Database.open(tmp.resolve("dados/outra.db"))) {
            // mesmo dia, mesma pasta de dados: as duas bases têm a sua cópia (uma não "ocupa" o dia da outra)
            Path daReal = Backups.automatica(db, dia1, 10).orElseThrow();
            Path daOutra = Backups.automatica(outra, dia1, 10).orElseThrow();
            assertEquals("oficina-20261001-090000.db", daReal.getFileName().toString());
            assertEquals("outra-20261001-090000.db", daOutra.getFileName().toString());
            assertEquals(tmp.resolve("dados/backups"), daReal.getParent());
            assertEquals(tmp.resolve("dados/outra-backups"), daOutra.getParent());
            assertEquals(List.of(daReal), Backups.listar(db));
            assertEquals(List.of(daOutra), Backups.listar(outra));

            // a rotação de uma não apaga cópias da outra
            for (int i = 1; i <= 4; i++) {
                Backups.automatica(outra, dia1.plusDays(i), 1);
            }
            assertEquals(1, Backups.listar(outra).size());
            assertEquals(List.of(daReal), Backups.listar(db));
            assertTrue(Files.exists(daReal));
            assertEquals("outra-20261005-090000.db", Backups.nomeSugerido(outra, dia1.plusDays(4)));
        }
    }

    @Test
    void copiasGuardadasAntesDeSubstituirNaoEntramNaRotacaoNemSeSobrepoem() throws SQLException, IOException {
        Path ficheiro = tmp.resolve("outro/oficina.db");
        Database.open(ficheiro).close();
        LocalDateTime agora = LocalDateTime.of(2026, 10, 1, 9, 0, 0);

        Path g1 = Backups.guardarAntesDeSubstituir(ficheiro, "antes-do-seed", agora);
        assertFalse(Files.exists(ficheiro), "a base saiu do sítio (foi movida, não apagada)");
        Database.open(ficheiro).close();
        Path g2 = Backups.guardarAntesDeSubstituir(ficheiro, "antes-do-seed", agora); // mesmo segundo
        assertEquals("antes-do-seed-20261001-090000.db", g1.getFileName().toString());
        assertEquals("antes-do-seed-20261001-090000-2.db", g2.getFileName().toString());
        assertEquals(tmp.resolve("outro/backups"), g1.getParent());

        // a rotação das cópias automáticas só apaga as suas
        try (Database nova = Database.open(ficheiro)) {
            for (int dia = 1; dia <= 3; dia++) {
                Backups.automatica(nova, agora.plusDays(dia), 1);
            }
            assertEquals(1, Backups.listar(nova).size());
        }
        assertTrue(Files.exists(g1) && Files.exists(g2));
    }

    @Test
    void ficheirosECopiasFicamSoParaODono() throws SQLException, IOException {
        assumeTrue(posix());
        Path copia = Backups.automatica(db, LocalDateTime.of(2026, 10, 1, 9, 0, 0), 3).orElseThrow();
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(db.ficheiro())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(copia)));
        // pastas criadas pela aplicação (dados/ e dados/backups/)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(db.ficheiro().getParent())));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(Backups.pasta(db))));
    }

    @Test
    void naoAlteraPermissoesDePastasQueJaExistem() throws SQLException, IOException {
        assumeTrue(posix());
        Path pasta = Files.createDirectory(tmp.resolve("existente"));
        Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("rwxr-xr-x"));
        try (Database d = Database.open(pasta.resolve("oficina.db"))) {
            assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(pasta)));
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(d.ficheiro())));
        }
    }
}
