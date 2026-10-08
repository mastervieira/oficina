package pt.oficina.db;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Onde a aplicação guarda os dados do utilizador: numa pasta do utilizador, fora da pasta da aplicação, para que
 * instalar uma versão nova (ou abrir a aplicação de outra pasta) nunca perca nem duplique a base de dados.
 *
 * <ul>
 *   <li>Linux: {@code $XDG_DATA_HOME/Oficina} ou {@code ~/.local/share/Oficina}</li>
 *   <li>Windows: {@code %APPDATA%\Oficina}</li>
 *   <li>macOS: {@code ~/Library/Application Support/Oficina}</li>
 * </ul>
 */
public final class PastaDeDados {

    static final String NOME_APP = "Oficina";
    static final String FICHEIRO_BD = "oficina.db";

    private static volatile Path migradaDe;

    private PastaDeDados() {
    }

    /** A pasta de dados deste utilizador neste sistema (não a cria). */
    public static Path pasta() {
        return pasta(System.getProperty("os.name", ""), System.getenv(), Path.of(System.getProperty("user.home")));
    }

    static Path pasta(String sistema, Map<String, String> ambiente, Path home) {
        String so = sistema.toLowerCase(Locale.ROOT);
        if (so.startsWith("windows")) {
            String appdata = ambiente.get("APPDATA");
            Path raiz = appdata == null || appdata.isBlank() ? home.resolve("AppData").resolve("Roaming") : Path.of(appdata);
            return raiz.resolve(NOME_APP);
        }
        if (so.startsWith("mac")) {
            return home.resolve("Library").resolve("Application Support").resolve(NOME_APP);
        }
        String xdg = ambiente.get("XDG_DATA_HOME");
        Path raiz = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                ? Path.of(xdg) : home.resolve(".local").resolve("share");
        return raiz.resolve(NOME_APP);
    }

    public static Path ficheiroDaBase() {
        return pasta().resolve(FICHEIRO_BD);
    }

    /** De onde se copiaram os dados nesta execução (a pasta {@code data/} antiga), ou null se não houve cópia. */
    public static Path migradaDe() {
        return migradaDe;
    }

    static void registarMigracao(Path origem) {
        migradaDe = origem;
    }

    /**
     * Até à versão 1.0.0 a base ficava em {@code data/oficina.db}, relativo à pasta de onde se abria a aplicação.
     * Se a base nova ainda não existe e a antiga sim, copia-a (e as cópias de segurança) para o sítio novo. A antiga
     * <b>não se apaga nem se altera</b>: fica como cópia. A base copia-se com VACUUM INTO (consistente mesmo com
     * ficheiros auxiliares do SQLite por aplicar) para um ficheiro temporário, que só depois passa a ser a base:
     * uma falha a meio nunca deixa uma base nova meio escrita.
     *
     * @return true se copiou
     */
    static boolean migrarDaPastaAntiga(Path antiga, Path nova) throws IOException, SQLException {
        if (Files.exists(nova) || !Files.isRegularFile(antiga)) {
            return false;
        }
        Database.criarPasta(Objects.requireNonNull(nova.getParent(), "a base nova tem de estar numa pasta"));
        Path tmp = nova.resolveSibling(Objects.requireNonNull(nova.getFileName()) + ".tmp");
        Files.deleteIfExists(tmp);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + antiga);
                PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
            ps.setString(1, tmp.toString());
            ps.execute();
        } catch (SQLException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Database.restringirAoDono(tmp, Database.SO_DONO_FICHEIRO);
        copiarCopiasDeSeguranca(Backups.pastaDe(antiga), Backups.pastaDe(nova));
        Files.move(tmp, nova); // por último: só com isto a base nova passa a existir
        return true;
    }

    /** Copia os ficheiros da pasta de cópias (sem entrar em subpastas nem seguir links), sem sobrepor nenhum. */
    private static void copiarCopiasDeSeguranca(Path de, Path para) throws IOException {
        if (!Files.isDirectory(de)) {
            return;
        }
        Database.criarPasta(para);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(de)) {
            for (Path f : ds) {
                Path destino = para.resolve(Objects.requireNonNull(f.getFileName()).toString());
                if (Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.exists(destino)) {
                    Files.copy(f, destino);
                    Database.restringirAoDono(destino, Database.SO_DONO_FICHEIRO);
                }
            }
        }
    }
}
