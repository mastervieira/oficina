package pt.oficina.db;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Cópias de segurança automáticas: no máximo uma por dia, mantendo só as mais recentes. Só se apagam ficheiros
 * com o nome das cópias automáticas.
 *
 * <p>Cada base de dados tem a sua pasta e o seu prefixo, para duas bases na mesma pasta (a real e uma de teste)
 * nunca se misturarem nem trocarem as cópias uma da outra. Para "oficina.db": pasta "backups", ficheiros
 * "oficina-AAAAMMDD-HHMMSS.db". Para "outra.db": pasta "outra-backups", ficheiros "outra-AAAAMMDD-HHMMSS.db".
 * (Em desenvolvimento usa-se uma só base; isto só importa se um dia houver duas lado a lado.)
 */
public final class Backups {

    public static final String PASTA = "backups";
    public static final int MANTER_POR_DEFEITO = 10;

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DIA_HORA = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String BASE_PADRAO = "oficina";

    private Backups() {
    }

    /** Pasta das cópias automáticas desta BD. */
    public static Path pasta(Database db) {
        return pastaDe(db.ficheiro());
    }

    /** Pasta das cópias de um ficheiro de BD ("backups" para oficina.db; "{nome}-backups" para as outras). */
    public static Path pastaDe(Path ficheiro) {
        String nome = nomeBase(ficheiro);
        return ficheiro.resolveSibling(nome.equals(BASE_PADRAO) ? PASTA : nome + "-" + PASTA);
    }

    /** Nome do ficheiro sem a extensão: "oficina.db" -> "oficina". */
    private static String nomeBase(Path ficheiro) {
        String nome = ficheiro.getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        return ponto > 0 ? nome.substring(0, ponto) : nome;
    }

    /** Nome sugerido para uma cópia desta BD feita agora (também usado no diálogo "Guardar como"). */
    public static String nomeSugerido(Database db, LocalDateTime agora) {
        return nomeSugerido(db.ficheiro(), agora);
    }

    private static String nomeSugerido(Path ficheiro, LocalDateTime agora) {
        return nomeBase(ficheiro) + "-" + DIA_HORA.format(agora) + ".db";
    }

    /**
     * Cria a cópia do dia se ainda não existir e apaga as mais antigas, ficando com {@code manter}.
     *
     * @return o ficheiro criado, ou vazio se já havia uma cópia de hoje
     */
    public static Optional<Path> automatica(Database db, LocalDateTime agora, int manter) throws SQLException {
        try {
            Path pasta = pasta(db);
            Database.criarPasta(pasta);
            String prefixoHoje = nomeBase(db.ficheiro()) + "-" + DIA.format(agora) + "-";
            if (listar(db).stream().anyMatch(p -> p.getFileName().toString().startsWith(prefixoHoje))) {
                return Optional.empty();
            }
            Path nova = pasta.resolve(nomeSugerido(db.ficheiro(), agora));
            db.copiarPara(nova);
            apagarAntigas(db, manter);
            return Optional.of(nova);
        } catch (IOException e) {
            throw new SQLException("Erro na cópia de segurança automática: " + e.getMessage(), e);
        }
    }

    /** Cópias automáticas existentes desta BD, da mais antiga para a mais recente. */
    public static List<Path> listar(Database db) throws IOException {
        List<Path> r = new ArrayList<>();
        Path pasta = pasta(db);
        if (!Files.isDirectory(pasta)) {
            return r;
        }
        Pattern nome = Pattern.compile(Pattern.quote(nomeBase(db.ficheiro()) + "-") + "\\d{8}-\\d{6}\\.db");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pasta)) {
            for (Path p : ds) {
                if (nome.matcher(p.getFileName().toString()).matches() && Files.isRegularFile(p)) {
                    r.add(p);
                }
            }
        }
        r.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return r;
    }

    private static final String[] AUXILIARES = {"-journal", "-wal", "-shm"};

    /**
     * Tira a BD do caminho {@code ficheiro} sem a apagar: move-a, com os ficheiros auxiliares do SQLite, para a pasta
     * de cópias, com o nome "{etiqueta}-AAAAMMDD-HHMMSS.db". Os auxiliares vão juntos porque um journal que ficasse
     * para trás podia ser aplicado por engano a uma base nova com o mesmo nome. Não entra na rotação das cópias
     * automáticas (só se apagam as que têm o nome delas), por isso fica até a apagares.
     *
     * @return onde a BD ficou guardada
     */
    public static Path guardarAntesDeSubstituir(Path ficheiro, String etiqueta, LocalDateTime agora)
            throws IOException {
        Path origem = ficheiro.toAbsolutePath().normalize();
        Path pasta = pastaDe(origem);
        Database.criarPasta(pasta);
        String base = etiqueta + "-" + DIA_HORA.format(agora);
        Path destino = pasta.resolve(base + ".db");
        for (int n = 2; Files.exists(destino); n++) { // duas no mesmo segundo: nunca sobrepor uma cópia
            destino = pasta.resolve(base + "-" + n + ".db");
        }
        mover(origem, destino);
        for (String sufixo : AUXILIARES) {
            Path auxiliar = Path.of(origem + sufixo);
            if (Files.exists(auxiliar)) {
                mover(auxiliar, Path.of(destino + sufixo));
            }
        }
        return destino;
    }

    /** Desfaz {@link #guardarAntesDeSubstituir}: devolve a BD guardada (e os auxiliares) ao caminho original. */
    public static void repor(Path guardada, Path ficheiro) throws IOException {
        Path destino = ficheiro.toAbsolutePath().normalize();
        mover(guardada, destino);
        for (String sufixo : AUXILIARES) {
            Path auxiliar = Path.of(guardada + sufixo);
            if (Files.exists(auxiliar)) {
                mover(auxiliar, Path.of(destino + sufixo));
            }
        }
    }

    private static void mover(Path de, Path para) throws IOException {
        try {
            Files.move(de, para, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(de, para, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void apagarAntigas(Database db, int manter) throws IOException {
        List<Path> todas = listar(db);
        for (int i = 0; i < todas.size() - Math.max(manter, 1); i++) {
            Files.deleteIfExists(todas.get(i));
        }
    }
}
