package pt.oficina.db;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Ligação única à BD SQLite (utilizador único). Ativa as chaves estrangeiras
 * e cria o esquema se a BD ainda estiver vazia.
 */
public final class Database implements AutoCloseable {

    /** Propriedade para alterar o ficheiro: -Doficina.db=/caminho/oficina.db */
    public static final String PROP_DB = "oficina.db";
    /** Onde a base ficava até à versão 1.0.0: relativo à pasta de trabalho. */
    private static final String LEGADO_REL = "data/oficina.db";

    static final Set<PosixFilePermission> SO_DONO_FICHEIRO = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> SO_DONO_PASTA = PosixFilePermissions.fromString("rwx------");

    private final Connection connection;
    private final Path ficheiro; // null em BD de memória

    private Database(Connection connection, Path ficheiro) {
        this.connection = connection;
        this.ficheiro = ficheiro;
    }

    /**
     * Ficheiro por defeito: {@code oficina.db} na pasta de dados do utilizador (ver {@link PastaDeDados}), ou o que
     * {@code -Doficina.db} indicar. Na primeira execução depois de a base ter mudado de sítio, copia para lá a
     * antiga ({@code data/oficina.db} na pasta de trabalho); se essa cópia falhar, continua a usar a antiga, que
     * é a que tem os dados, e avisa no terminal.
     */
    public static Path defaultPath() {
        String indicado = System.getProperty(PROP_DB);
        if (indicado != null) {
            return Path.of(indicado).toAbsolutePath();
        }
        Path nova = PastaDeDados.ficheiroDaBase();
        Path antiga = Path.of(LEGADO_REL).toAbsolutePath();
        try {
            if (PastaDeDados.migrarDaPastaAntiga(antiga, nova)) {
                PastaDeDados.registarMigracao(antiga);
            }
            return nova;
        } catch (IOException | SQLException e) {
            System.err.println("Não foi possível copiar a base de dados antiga para " + nova + ": " + e.getMessage()
                    + "\nContinua a usar " + antiga);
            return antiga;
        }
    }

    public static Database open(Path file) throws SQLException {
        Path abs = file.toAbsolutePath().normalize();
        try {
            Path parent = abs.getParent();
            if (parent != null) {
                criarPasta(parent);
            }
        } catch (IOException e) {
            throw new SQLException("Não foi possível criar a pasta da BD: " + file, e);
        }
        Database db = connect("jdbc:sqlite:" + abs, abs);
        restringirAoDono(abs, SO_DONO_FICHEIRO);
        return db;
    }

    /** BD em memória; usada nos testes. */
    public static Database inMemory() throws SQLException {
        return connect("jdbc:sqlite::memory:", null);
    }

    private static Database connect(String url, Path ficheiro) throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            Database db = new Database(c, ficheiro);
            db.copiaAntesDeMigrar();
            SchemaInitializer.apply(c);
            return db;
        } catch (SQLException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    /**
     * Antes de migrar uma base de dados existente para um esquema novo, guarda uma cópia em backups/
     * ("antes-da-migracao-vN-...db"). Estas cópias não entram na rotação das automáticas: ficam até as apagares.
     */
    private void copiaAntesDeMigrar() throws SQLException {
        int versao = SchemaInitializer.versao(connection);
        if (ficheiro == null || versao == 0 || versao >= SchemaInitializer.VERSAO_ATUAL) {
            return;
        }
        try {
            Path pasta = Backups.pastaDe(ficheiro);
            criarPasta(pasta);
            copiarPara(pasta.resolve("antes-da-migracao-v" + versao + "-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".db"));
        } catch (IOException e) {
            throw new SQLException("Não foi possível guardar a cópia antes de migrar a base de dados: "
                    + e.getMessage(), e);
        }
    }

    public Connection connection() {
        return connection;
    }

    /** Ficheiro da BD, ou null se for uma BD de memória. */
    public Path ficheiro() {
        return ficheiro;
    }

    /**
     * Grava uma cópia consistente da BD em {@code destino} (VACUUM INTO), mesmo com a aplicação a usá-la.
     * A cópia faz-se para um ficheiro temporário e só depois substitui o destino, por isso um destino já
     * existente é substituído de uma só vez e nunca fica meio escrito.
     */
    public void copiarPara(Path destino) throws SQLException {
        if (ficheiro == null) {
            throw new IllegalStateException("Uma BD em memória não tem ficheiro para copiar.");
        }
        try {
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("Não se pode copiar a BD com uma transação em curso.");
            }
            Path dest = destino.toAbsolutePath().normalize();
            if (Files.exists(dest) && Files.isSameFile(dest, ficheiro)) {
                throw new SQLException("A cópia não pode substituir a própria base de dados.");
            }
            Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            try (PreparedStatement ps = connection.prepareStatement("VACUUM INTO ?")) {
                ps.setString(1, tmp.toString());
                ps.execute();
            } catch (SQLException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            restringirAoDono(tmp, SO_DONO_FICHEIRO);
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new SQLException("Erro ao gravar a cópia: " + e.getMessage(), e);
        }
    }

    /** Cria a pasta (e as que faltarem) acessível só ao dono; pastas que já existem não são alteradas. */
    static void criarPasta(Path pasta) throws IOException {
        try {
            Files.createDirectories(pasta, PosixFilePermissions.asFileAttribute(SO_DONO_PASTA));
        } catch (UnsupportedOperationException e) { // sistema de ficheiros sem permissões POSIX
            Files.createDirectories(pasta);
        }
    }

    /** Ficheiros da BD (e das cópias) legíveis só pelo dono: contêm, por exemplo, contactos de fornecedores. */
    static void restringirAoDono(Path ficheiro, Set<PosixFilePermission> permissoes) {
        try {
            Files.setPosixFilePermissions(ficheiro, permissoes);
        } catch (UnsupportedOperationException | IOException e) {
            // Sem suporte POSIX ou sem direitos para alterar: não impede o uso da aplicação.
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
