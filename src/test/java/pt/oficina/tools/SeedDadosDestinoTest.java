package pt.oficina.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pt.oficina.db.Database;
import pt.oficina.model.enums.TipoCategoria;
import pt.oficina.service.InventarioService;

/** Onde o seed escreve: base nova, base vazia, base com dados, --recriar e falhas a meio. */
class SeedDadosDestinoTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 5);
    private static final SeedDados.Parametros PEQUENO = new SeedDados.Parametros(8, 20, 40, 42L, HOJE);
    private static final SeedDados.Gerar FALHA = (c, p) -> {
        throw new SQLException("falha simulada");
    };

    @TempDir
    Path tmp;

    private Path base() {
        return tmp.resolve("data/oficina.db");
    }

    private Path pastaDeCopias() {
        return tmp.resolve("data/backups");
    }

    /** Uma base com um único registo do utilizador: uma categoria. */
    private Path baseComDados() throws SQLException {
        try (Database db = Database.open(base())) {
            new InventarioService(db.connection()).criarCategoria("Minha", TipoCategoria.MAQUINA);
        }
        return base();
    }

    private static int contar(Path ficheiro, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro);
                Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private List<Path> copias() throws IOException {
        if (!Files.isDirectory(pastaDeCopias())) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(pastaDeCopias())) {
            return s.sorted().toList();
        }
    }

    // ---- onde se pode escrever ------------------------------------------------

    @Test
    void criaAPastaEOFicheiroSeNaoExistirem() throws Exception {
        SeedDados.Resultado r = SeedDados.executar(base(), PEQUENO, false);
        assertNull(r.copiaDoAnterior());
        assertEquals(8, contar(base(), "SELECT COUNT(*) FROM maquina"));
        assertEquals(40, contar(base(), "SELECT COUNT(*) FROM artigo"));
        assertEquals(List.of(), copias());
    }

    @Test
    void populaUmaBaseVaziaQueJaExiste() throws Exception {
        Database.open(base()).close(); // o que a aplicação cria no primeiro arranque
        assertEquals(0, contar(base(), "SELECT COUNT(*) FROM maquina"));
        SeedDados.Resultado r = SeedDados.executar(base(), PEQUENO, false);
        assertNull(r.copiaDoAnterior(), "não havia nada para guardar");
        assertEquals(8, contar(base(), "SELECT COUNT(*) FROM maquina"));
        assertEquals(List.of(), copias());
    }

    @Test
    void populaUmFicheiroDeZeroBytes() throws Exception {
        Files.createDirectories(base().getParent());
        Files.createFile(base());
        SeedDados.executar(base(), PEQUENO, false);
        assertEquals(8, contar(base(), "SELECT COUNT(*) FROM maquina"));
    }

    @Test
    void recusaUmaBaseComDadosSemAlterarNada() throws Exception {
        baseComDados();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SeedDados.executar(base(), PEQUENO, false));
        assertTrue(e.getMessage().contains("já tem dados"), e.getMessage());
        assertTrue(e.getMessage().contains("1 categorias"), e.getMessage());
        assertTrue(e.getMessage().contains("--recriar"), e.getMessage());
        assertEquals(1, contar(base(), "SELECT COUNT(*) FROM categoria"));
        assertEquals(0, contar(base(), "SELECT COUNT(*) FROM maquina"));
        assertEquals(List.of(), copias());
    }

    @Test
    void qualquerTabelaComDadosBastaParaRecusar() throws Exception {
        try (Database db = Database.open(base())) {
            new InventarioService(db.connection()).criarLocalizacao("Só uma localização");
        }
        assertThrows(IllegalStateException.class, () -> SeedDados.executar(base(), PEQUENO, false));
        assertEquals(1, contar(base(), "SELECT COUNT(*) FROM localizacao"));
    }

    @Test
    void ficheiroQueNaoESqliteERecusadoMasGuardadoComRecriar() throws Exception {
        Files.createDirectories(base().getParent());
        Files.writeString(base(), "isto não é uma base de dados");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SeedDados.executar(base(), PEQUENO, false));
        assertTrue(e.getMessage().contains("não é uma base de dados SQLite"), e.getMessage());
        assertEquals("isto não é uma base de dados", Files.readString(base()), "intacto");

        SeedDados.Resultado r = SeedDados.executar(base(), PEQUENO, true);
        assertEquals("isto não é uma base de dados", Files.readString(r.copiaDoAnterior()), "guardado à parte, intacto");
        assertEquals(8, contar(base(), "SELECT COUNT(*) FROM maquina"));
    }

    @Test
    void umaPastaNaoEUmaBaseDeDados() throws Exception {
        Files.createDirectories(base());
        assertThrows(IllegalArgumentException.class, () -> SeedDados.executar(base(), PEQUENO, true));
        assertTrue(Files.isDirectory(base()));
        assertEquals(List.of(), copias());
    }

    // ---- --recriar ------------------------------------------------------------

    @Test
    void recriarGuardaABaseAnteriorSemApagarNada() throws Exception {
        baseComDados();
        SeedDados.Resultado r = SeedDados.executar(base(), PEQUENO, true);

        Path copia = r.copiaDoAnterior();
        assertEquals(pastaDeCopias(), copia.getParent());
        assertTrue(copia.getFileName().toString().matches("antes-do-seed-\\d{8}-\\d{6}\\.db"), copia.toString());
        assertEquals(List.of(copia), copias());
        // a cópia é a base como estava: a categoria do utilizador e nada do seed
        assertEquals(1, contar(copia, "SELECT COUNT(*) FROM categoria WHERE nome = 'Minha'"));
        assertEquals(0, contar(copia, "SELECT COUNT(*) FROM maquina"));
        // a base nova tem o seed e já não tem a categoria antiga
        assertEquals(8, contar(base(), "SELECT COUNT(*) FROM maquina"));
        assertEquals(0, contar(base(), "SELECT COUNT(*) FROM categoria WHERE nome = 'Minha'"));
    }

    @Test
    void recriarSemNadaParaGuardarSoCria() throws Exception {
        SeedDados.Resultado r = SeedDados.executar(base(), PEQUENO, true);
        assertNull(r.copiaDoAnterior());
        assertEquals(8, contar(base(), "SELECT COUNT(*) FROM maquina"));
        assertEquals(List.of(), copias());
    }

    @Test
    void recriarLevaOsFicheirosAuxiliaresParaNaoContaminarABaseNova() throws Exception {
        baseComDados();
        Files.writeString(Path.of(base() + "-journal"), "lixo-journal");
        Files.writeString(Path.of(base() + "-wal"), "lixo-wal");

        SeedDados.Resultado r = SeedDados.executar(base(), PEQUENO, true);

        assertFalse(Files.exists(Path.of(base() + "-journal")), "um journal antigo não pode ficar ao lado da base nova");
        assertFalse(Files.exists(Path.of(base() + "-wal")));
        assertEquals("lixo-journal", Files.readString(Path.of(r.copiaDoAnterior() + "-journal")));
        assertEquals("lixo-wal", Files.readString(Path.of(r.copiaDoAnterior() + "-wal")));
        assertEquals(8, contar(base(), "SELECT COUNT(*) FROM maquina"));
        assertEquals(0, contar(base(), "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        try (Database db = Database.open(base()); Statement st = db.connection().createStatement();
                ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            rs.next();
            assertEquals("ok", rs.getString(1));
        }
    }

    @Test
    void duasRecriacoesSeguidasGuardamAsDuasBasesAnteriores() throws Exception {
        baseComDados();
        SeedDados.Resultado primeira = SeedDados.executar(base(), PEQUENO, true);
        SeedDados.Resultado segunda = SeedDados.executar(base(), PEQUENO, true); // o seed da primeira passa a ser guardado
        assertNotEquals(primeira.copiaDoAnterior(), segunda.copiaDoAnterior());
        assertEquals(2, copias().size());
        assertEquals(1, contar(primeira.copiaDoAnterior(), "SELECT COUNT(*) FROM categoria WHERE nome = 'Minha'"));
        assertEquals(8, contar(segunda.copiaDoAnterior(), "SELECT COUNT(*) FROM maquina"));
    }

    // ---- falhas ---------------------------------------------------------------

    @Test
    void falhaNumFicheiroNovoNaoDeixaNada() throws Exception {
        SQLException e = assertThrows(SQLException.class, () -> SeedDados.executar(base(), PEQUENO, false, FALHA));
        assertEquals("falha simulada", e.getMessage());
        assertFalse(Files.exists(base()), "o ficheiro criado por esta execução não pode ficar a meio");
        assertFalse(Files.exists(Path.of(base() + "-journal")));
    }

    @Test
    void falhaNumaBaseVaziaQueJaExistiaDeixaAIntacta() throws Exception {
        Database.open(base()).close();
        assertThrows(SQLException.class, () -> SeedDados.executar(base(), PEQUENO, false, FALHA));
        assertTrue(Files.exists(base()));
        assertEquals(0, contar(base(), "SELECT COUNT(*) FROM maquina")); // o esquema continua lá
    }

    @Test
    void falhaDepoisDeRecriarRepoeAOriginalNoSeuLugar() throws Exception {
        baseComDados();
        Files.writeString(Path.of(base() + "-journal"), "x");

        assertThrows(SQLException.class, () -> SeedDados.executar(base(), PEQUENO, true, FALHA));

        assertEquals(List.of(), copias(), "a cópia voltou ao sítio de origem");
        assertEquals("x", Files.readString(Path.of(base() + "-journal")), "o auxiliar voltou com a base");
        Files.delete(Path.of(base() + "-journal")); // era lixo de teste; não o deixar interferir com a leitura
        assertEquals(1, contar(base(), "SELECT COUNT(*) FROM categoria WHERE nome = 'Minha'"));
        assertEquals(0, contar(base(), "SELECT COUNT(*) FROM maquina"));
    }

    @Test
    void parametrosInvalidosNaoTocamEmNadaMesmoComRecriar() throws Exception {
        baseComDados();
        assertThrows(IllegalArgumentException.class,
                () -> SeedDados.executar(base(), new SeedDados.Parametros(3, 20, 40, 1L, HOJE), true));
        assertEquals(1, contar(base(), "SELECT COUNT(*) FROM categoria WHERE nome = 'Minha'"));
        assertEquals(List.of(), copias());
    }

    // ---- argumentos -------------------------------------------------------------

    @Test
    void semArgumentosUsaAsPredefinicoes() {
        SeedDados.Argumentos a = SeedDados.analisar(new String[0]);
        assertNull(a.ficheiro(), "sem ficheiro: usa-se a base da aplicação");
        assertFalse(a.recriar());
        assertFalse(a.ajuda());
        assertEquals(100, a.parametros().maquinas());
        assertEquals(500, a.parametros().localizacoes());
        assertEquals(10_000, a.parametros().artigos());
        assertEquals(42L, a.parametros().semente());
    }

    @Test
    void interpretaTodasAsOpcoesEmQualquerOrdem() {
        SeedDados.Argumentos a = SeedDados.analisar(new String[] {"--artigos", "99", "x.db", "--recriar",
            "--semente", "123456789012", "--maquinas", "12", "--localizacoes", "30"});
        assertEquals(Path.of("x.db"), a.ficheiro());
        assertTrue(a.recriar());
        assertEquals(99, a.parametros().artigos());
        assertEquals(12, a.parametros().maquinas());
        assertEquals(30, a.parametros().localizacoes());
        assertEquals(123456789012L, a.parametros().semente());
        assertTrue(SeedDados.analisar(new String[] {"--ajuda"}).ajuda());
    }

    @Test
    void recusaArgumentosInvalidos() {
        for (String[] mau : new String[][] {
            {"--desconhecida"}, {"--artigos"}, {"--artigos", "muitos"}, {"--artigos", "99999999999"},
            {"--maquinas", "1.5"}, {"a.db", "b.db"}}) {
            assertThrows(IllegalArgumentException.class, () -> SeedDados.analisar(mau), String.join(" ", mau));
        }
    }
}
