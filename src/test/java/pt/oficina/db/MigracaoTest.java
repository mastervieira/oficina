package pt.oficina.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Migração do esquema da versão 1 (CHECK antigos) para a versão 2. */
class MigracaoTest {

    private static final List<String> TABELAS = List.of("categoria", "localizacao", "fornecedor", "maquina",
            "artigo", "movimento_stock", "plano_manutencao", "intervencao", "historico_estado");

    @TempDir
    Path tmp;

    // ---- utilitários -----------------------------------------------------

    /** Cria uma base com o esquema da versão 1 (a que existia antes) e dados em todas as tabelas. */
    private Path criarBaseV1(String nome) throws SQLException, IOException {
        Path ficheiro = tmp.resolve(nome);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro); Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            try (InputStream in = MigracaoTest.class.getResourceAsStream("/db/schema-v1.sql")) {
                assertNotNull(in, "falta src/test/resources/db/schema-v1.sql");
                for (String bloco : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("(?m)^-- @@\\s*$")) {
                    if (!bloco.isBlank()) {
                        st.execute(bloco);
                    }
                }
            }
            st.execute("INSERT INTO categoria(nome, tipo) VALUES ('Tornos','MAQUINA'), ('Pastilhas','ARTIGO')");
            st.execute("INSERT INTO localizacao(nome) VALUES ('Armazém')");
            st.execute("INSERT INTO fornecedor(nome, contacto) VALUES ('Sandvik', '210 000 000')");
            st.execute("INSERT INTO maquina(codigo, descricao, n_serie, categoria_id, localizacao_id, fornecedor_id, "
                    + "data_aquisicao, estado) VALUES ('T01','Torno','S1',1,1,1,'2020-05-01','ATIVO'), "
                    + "('F01','Fresa',NULL,1,NULL,NULL,NULL,'INOPERATIVO')");
            st.execute("INSERT INTO artigo(codigo, descricao, categoria_id, unidade, stock_minimo) "
                    + "VALUES ('A01','Pastilha',2,'un',5)");
            st.execute("INSERT INTO movimento_stock(artigo_id, data_mov, tipo, quantidade, nota) "
                    + "VALUES (1,'2026-01-01','ENTRADA',10,NULL), (1,'2026-02-01','SAIDA',3,'Peça X')");
            st.execute("INSERT INTO plano_manutencao(maquina_id, tarefa, periodicidade_meses) VALUES (1,'Lubrificar',3)");
            st.execute("INSERT INTO intervencao(maquina_id, plano_id, data_interv, tipo, descricao, custo) "
                    + "VALUES (1,1,'2026-03-01','PREVENTIVA','ok',12.5), (2,NULL,'2026-04-01','CORRETIVA',NULL,NULL)");
            st.execute("INSERT INTO historico_estado(maquina_id, data_estado, estado, motivo) "
                    + "VALUES (1,'2020-05-01','ATIVO','Registo inicial'), (2,'2021-01-01','ATIVO','Registo inicial'), "
                    + "(2,'2026-04-01','INOPERATIVO','Avaria')");
            st.execute("PRAGMA user_version = 1");
        }
        return ficheiro;
    }

    private static List<String> linhas(Connection c, String tabela) throws SQLException {
        List<String> r = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + tabela + " ORDER BY id")) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    if (!md.getColumnName(i).equals("imagem")) { // colunas da v5 e da v6: as linhas antigas não as têm
                        sb.append(rs.getString(i)).append('|');
                    }
                }
                r.add(sb.toString());
            }
        }
        return r;
    }

    private static Map<String, List<String>> todasAsLinhas(Connection c) throws SQLException {
        Map<String, List<String>> r = new TreeMap<>();
        for (String t : TABELAS) {
            r.put(t, linhas(c, t));
        }
        return r;
    }

    /** Definições (tabelas, índices, triggers) normalizadas: sem aspas nem diferenças de espaços. */
    private static Map<String, String> objetos(Connection c) throws SQLException {
        Map<String, String> r = new TreeMap<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL")) {
            while (rs.next()) {
                // o ALTER TABLE ADD COLUMN do SQLite escreve a coluna nova com outro espaçamento: ignora-se à volta de , ( )
                r.put(rs.getString(1), rs.getString(2).replace("\"", "").replaceAll("\\s*([(),])\\s*", "$1")
                        .replaceAll("\\s+", " ").trim());
            }
        }
        return r;
    }

    private static String escalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static List<Path> copiasAntesDeMigrar(Path pasta, int versao) throws IOException {
        List<Path> r = new ArrayList<>();
        if (Files.isDirectory(pasta)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(pasta, "antes-da-migracao-v" + versao + "-*.db")) {
                ds.forEach(r::add);
            }
        }
        return r;
    }

    /** Base como a deixou a versão 2 da aplicação: a v1 mais a migração 2 (CHECK alargados, triggers antigos). */
    private Path criarBaseV2(String nome) throws SQLException, IOException {
        Path ficheiro = criarBaseV1(nome);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro); Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = OFF");
            try (InputStream in = MigracaoTest.class.getResourceAsStream("/db/migracao-2.sql")) {
                for (String bloco : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("(?m)^-- @@\\s*$")) {
                    if (!bloco.isBlank()) {
                        st.execute(bloco);
                    }
                }
            }
            st.execute("PRAGMA user_version = 2");
        }
        return ficheiro;
    }

    /** O esquema como o deixou a versão 3 da aplicação (v1 + migrações 2 e 3), sem dados. */
    private Path criarEsquemaV3(String nome) throws SQLException, IOException {
        return criarEsquema(nome, 3);
    }

    /** O esquema como o deixou a versão {@code versao} da aplicação (v1 + migrações 2 a {@code versao}), sem dados. */
    private Path criarEsquema(String nome, int versao) throws SQLException, IOException {
        Path ficheiro = tmp.resolve(nome);
        List<String> recursos = new ArrayList<>(List.of("/db/schema-v1.sql"));
        for (int v = 2; v <= versao; v++) {
            recursos.add("/db/migracao-" + v + ".sql");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro); Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = OFF");
            for (String recurso : recursos) {
                try (InputStream in = MigracaoTest.class.getResourceAsStream(recurso)) {
                    assertNotNull(in, recurso);
                    for (String bloco : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("(?m)^-- @@\\s*$")) {
                        if (!bloco.isBlank()) {
                            st.execute(bloco);
                        }
                    }
                }
            }
            st.execute("PRAGMA user_version = " + versao);
        }
        return ficheiro;
    }

    // ---- testes ----------------------------------------------------------

    @Test
    void migraOsDadosSemAlterarNada() throws Exception {
        Path ficheiro = criarBaseV1("oficina.db");
        Map<String, List<String>> antes;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro)) {
            antes = todasAsLinhas(c);
        }
        assertEquals(2, antes.get("maquina").size());

        try (Database db = Database.open(ficheiro)) {
            Connection c = db.connection();
            assertEquals(String.valueOf(SchemaInitializer.VERSAO_ATUAL), escalar(c, "PRAGMA user_version"));
            assertEquals("1", escalar(c, "PRAGMA foreign_keys"), "as chaves estrangeiras voltam a ficar ligadas");
            assertEquals(antes, todasAsLinhas(c));
            assertNull(escalar(c, "SELECT imagem FROM artigo WHERE codigo = 'A01'"), "artigos antigos ficam sem imagem");
            assertEquals("0", escalar(c, "SELECT COUNT(*) FROM maquina WHERE imagem IS NOT NULL"),
                    "máquinas antigas ficam sem imagem");
            assertEquals("ok", escalar(c, "PRAGMA integrity_check"));
            assertNull(escalar(c, "PRAGMA foreign_key_check"));
        }
    }

    @Test
    void esquemaMigradoEIgualAoDeUmaBaseNova() throws Exception {
        Path ficheiro = criarBaseV1("oficina.db");
        Map<String, String> migrado;
        try (Database db = Database.open(ficheiro)) {
            migrado = objetos(db.connection());
        }
        Map<String, String> novo;
        try (Database db = Database.inMemory()) {
            novo = objetos(db.connection());
        }
        // Nomes dos objetos (tabelas, índices, triggers) e respetivas definições, iguais um a um.
        assertEquals(novo.keySet(), migrado.keySet());
        for (String nome : novo.keySet()) {
            assertEquals(novo.get(nome), migrado.get(nome), "definição de " + nome);
        }
        assertFalse(migrado.keySet().stream().anyMatch(n -> n.endsWith("_nova")), "sobras da migração");
    }

    @Test
    void aceitaOsValoresNovosEContinuaARecusarOutros() throws Exception {
        try (Database db = Database.open(criarBaseV1("oficina.db"))) {
            Connection c = db.connection();
            exec(c, "UPDATE maquina SET estado = 'MANUTENCAO' WHERE codigo = 'T01'");
            exec(c, "INSERT INTO historico_estado(maquina_id, data_estado, estado, motivo) "
                    + "VALUES (1, '2026-05-01', 'MANUTENCAO', 'Revisão')");
            exec(c, "INSERT INTO intervencao(maquina_id, plano_id, data_interv, tipo) "
                    + "VALUES (1, 1, '2026-05-01', 'PROGRAMADA')");
            exec(c, "INSERT INTO categoria(nome, tipo) VALUES ('Brocas','FERRAMENTA'), ('Óleos','CONSUMIVEL'), ('Vários','OUTROS')");
            assertThrows(SQLException.class, () -> exec(c, "UPDATE maquina SET estado = 'AVARIADO'"));
            assertThrows(SQLException.class, () -> exec(c, "INSERT INTO categoria(nome, tipo) VALUES ('x','OUTRO')"));
            assertThrows(SQLException.class, () -> exec(c,
                    "INSERT INTO intervencao(maquina_id, data_interv, tipo) VALUES (1, '2026-05-01', 'URGENTE')"));
        }
    }

    @Test
    void osIdentificadoresContinuamDeOndeEstavam() throws Exception {
        try (Database db = Database.open(criarBaseV1("oficina.db"))) {
            Connection c = db.connection();
            exec(c, "INSERT INTO categoria(nome, tipo) VALUES ('Brocas','FERRAMENTA')");
            exec(c, "INSERT INTO maquina(codigo, descricao, categoria_id) VALUES ('T02','Outro torno',1)");
            exec(c, "INSERT INTO historico_estado(maquina_id, data_estado, estado) VALUES (3,'2026-05-01','ATIVO')");
            assertEquals("3", escalar(c, "SELECT id FROM categoria WHERE nome = 'Brocas'"));
            assertEquals("3", escalar(c, "SELECT id FROM maquina WHERE codigo = 'T02'"));
            assertEquals("4", escalar(c, "SELECT MAX(id) FROM historico_estado"));
        }
    }

    @Test
    void asProtecoesSobrevivemAMigracao() throws Exception {
        try (Database db = Database.open(criarBaseV1("oficina.db"))) {
            Connection c = db.connection();
            // apagar o que tem registos associados continua a ser recusado (agora pelas chaves estrangeiras)
            assertThrows(SQLException.class, () -> exec(c, "DELETE FROM maquina"));
            assertThrows(SQLException.class, () -> exec(c, "DELETE FROM artigo"));
            assertThrows(SQLException.class, () -> exec(c, "UPDATE historico_estado SET motivo = 'x'"));
            assertThrows(SQLException.class, () -> exec(c, "DELETE FROM historico_estado"));
            exec(c, "INSERT INTO categoria(nome, tipo) VALUES ('Fresadoras','MAQUINA')"); // outro tipo de máquina
            exec(c, "INSERT INTO plano_manutencao(categoria_id, tarefa, periodicidade_meses) VALUES (3, 'Verificar', 6)");
            // o plano 2 é do tipo Fresadoras: uma intervenção de uma máquina do tipo Tornos não pode ligar-se a ele
            assertThrows(SQLException.class, () -> exec(c,
                    "INSERT INTO intervencao(maquina_id, plano_id, data_interv, tipo) VALUES (1, 2, '2026-05-01', 'PREVENTIVA')"));
            // chaves estrangeiras ativas
            assertThrows(SQLException.class, () -> exec(c,
                    "INSERT INTO maquina(codigo, descricao, categoria_id) VALUES ('X','x',999)"));
        }
    }

    @Test
    void depoisDeMigrarApagaSeOQueNuncaFoiUsado() throws Exception {
        try (Database db = Database.open(criarBaseV1("oficina.db"))) {
            Connection c = db.connection();
            exec(c, "INSERT INTO maquina(codigo, descricao, categoria_id) VALUES ('T02','Nova',1)");
            exec(c, "INSERT INTO historico_estado(maquina_id, data_estado, estado, motivo) VALUES (3,'2026-05-01','ATIVO','Registo inicial')");
            exec(c, "INSERT INTO artigo(codigo, descricao, categoria_id) VALUES ('A02','Novo',2)");
            exec(c, "DELETE FROM historico_estado WHERE maquina_id = 3");
            exec(c, "DELETE FROM maquina WHERE id = 3");
            exec(c, "DELETE FROM artigo WHERE codigo = 'A02'");
            // a máquina 2 tem 3 linhas de histórico: o histórico continua protegido
            assertThrows(SQLException.class, () -> exec(c, "DELETE FROM historico_estado WHERE maquina_id = 2"));
        }
    }

    @Test
    void migraDaVersao2ParaA3SemTocarNosDados() throws Exception {
        Path ficheiro = criarBaseV2("oficina.db");
        Map<String, List<String>> antes;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro)) {
            assertEquals("2", escalar(c, "PRAGMA user_version"));
            exec(c, "UPDATE maquina SET estado = 'MANUTENCAO' WHERE codigo = 'F01'"); // valor da versão 2
            antes = todasAsLinhas(c);
            assertEquals("trg_maquina_sem_delete", escalar(c,
                    "SELECT name FROM sqlite_master WHERE name = 'trg_maquina_sem_delete'"));
        }
        Map<String, String> novo;
        try (Database db = Database.inMemory()) {
            novo = objetos(db.connection());
        }
        try (Database db = Database.open(ficheiro)) {
            Connection c = db.connection();
            assertEquals(String.valueOf(SchemaInitializer.VERSAO_ATUAL), escalar(c, "PRAGMA user_version"));
            assertEquals(antes, todasAsLinhas(c));
            assertEquals(novo, objetos(c), "esquema migrado == esquema de uma base nova");
            assertNull(escalar(c, "SELECT name FROM sqlite_master WHERE name = 'trg_maquina_sem_delete'"));
            assertNull(escalar(c, "SELECT name FROM sqlite_master WHERE name = 'trg_artigo_sem_delete'"));
            assertEquals("trg_historico_sem_update", escalar(c,
                    "SELECT name FROM sqlite_master WHERE name = 'trg_historico_sem_update'"));
            exec(c, "UPDATE maquina SET estado = 'MANUTENCAO'"); // continua válido
            assertEquals("1", escalar(c, "PRAGMA foreign_keys"));
        }
        // Uma só cópia, da versão de onde se partiu (2), e nenhuma da v1.
        Path pasta = ficheiro.resolveSibling("backups");
        assertEquals(1, copiasAntesDeMigrar(pasta, 2).size());
        assertEquals(0, copiasAntesDeMigrar(pasta, 1).size());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + copiasAntesDeMigrar(pasta, 2).get(0))) {
            assertEquals("2", escalar(c, "PRAGMA user_version"));
        }
    }

    @Test
    void planosPorMaquinaPassamAPlanosPorTipoFundindoOsRepetidos() throws Exception {
        Path ficheiro = criarEsquemaV3("oficina.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro)) {
            exec(c, "INSERT INTO categoria(nome, tipo) VALUES ('Tornos','MAQUINA'), ('Fresadoras','MAQUINA')");
            exec(c, "INSERT INTO maquina(codigo, descricao, categoria_id) VALUES ('T01','Torno 1',1), ('T02','Torno 2',1), ('F01','Fresa',2)");
            // planos por máquina (esquema antigo): T01 e T02 têm a mesma tarefa («Lubrificar», com maiúsculas
            // diferentes e periodicidades 3 e 6); a F01 tem uma «Lubrificar» de 6, que é de outro tipo
            exec(c, "INSERT INTO plano_manutencao(maquina_id, tarefa, periodicidade_meses) VALUES "
                    + "(1,'Lubrificar',3), (2,'lubrificar',6), (2,'Filtros',12), (3,'Lubrificar',6), (1,'Revisão',1)");
            exec(c, "INSERT INTO intervencao(maquina_id, plano_id, data_interv, tipo) VALUES "
                    + "(1,1,'2026-01-10','PREVENTIVA'), (2,2,'2026-02-10','PREVENTIVA'), (2,3,'2026-03-10','PROGRAMADA'), "
                    + "(3,4,'2026-04-10','PREVENTIVA')");
            exec(c, "INSERT INTO intervencao(maquina_id, data_interv, tipo, custo) VALUES (1,'2026-05-10','CORRETIVA',99.5)");
        }

        try (Database db = Database.open(ficheiro)) {
            Connection c = db.connection();
            assertEquals(String.valueOf(SchemaInitializer.VERSAO_ATUAL), escalar(c, "PRAGMA user_version"));
            // 5 planos por máquina -> 4 planos por tipo (os dois «Lubrificar» de Tornos fundiram-se; fica a periodicidade MAIS CURTA)
            assertEquals(List.of("1|1|Filtros|12|", "2|1|Lubrificar|3|", "3|1|Revisão|1|", "4|2|Lubrificar|6|"),
                    linhas(c, "plano_manutencao"));
            // cada intervenção aponta agora para o plano do TIPO da sua máquina
            assertEquals(List.of("1|1|2|2026-01-10", "2|2|2|2026-02-10", "3|2|1|2026-03-10", "4|3|4|2026-04-10", "5|1|null|2026-05-10"),
                    textos(c, "SELECT id, maquina_id, plano_id, data_interv FROM intervencao ORDER BY id"));
            assertNull(escalar(c, "PRAGMA foreign_key_check"));
            assertEquals("ok", escalar(c, "PRAGMA integrity_check"));
            // o gatilho novo: só máquinas do tipo do plano; o antigo desapareceu
            exec(c, "INSERT INTO intervencao(maquina_id, plano_id, data_interv, tipo) VALUES (2, 2, '2026-06-01', 'PREVENTIVA')");
            assertThrows(SQLException.class, () -> exec(c,
                    "INSERT INTO intervencao(maquina_id, plano_id, data_interv, tipo) VALUES (3, 2, '2026-06-01', 'PREVENTIVA')"));
            assertNull(escalar(c, "SELECT name FROM sqlite_master WHERE name = 'trg_intervencao_plano_maquina'"));
            // tarefa única por tipo
            assertThrows(SQLException.class, () -> exec(c,
                    "INSERT INTO plano_manutencao(categoria_id, tarefa, periodicidade_meses) VALUES (1, 'FILTROS', 1)"));
            // os identificadores continuam a partir do último
            exec(c, "INSERT INTO plano_manutencao(categoria_id, tarefa, periodicidade_meses) VALUES (2, 'Nova', 2)");
            assertEquals("5", escalar(c, "SELECT id FROM plano_manutencao WHERE tarefa = 'Nova'"));
        }
        // cópia feita antes de migrar, na versão em que estava, com os planos por máquina
        List<Path> copias = copiasAntesDeMigrar(ficheiro.resolveSibling("backups"), 3);
        assertEquals(1, copias.size());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + copias.get(0))) {
            assertEquals("3", escalar(c, "PRAGMA user_version"));
            assertEquals("5", escalar(c, "SELECT COUNT(*) FROM plano_manutencao"));
            assertEquals("1", escalar(c, "SELECT COUNT(*) FROM pragma_table_info('plano_manutencao') WHERE name = 'maquina_id'"));
        }
    }

    @Test
    void daVersao5ParaA6AMaquinaGanhaImagemEAsDosArtigosFicam() throws Exception {
        Path ficheiro = criarEsquema("oficina.db", 5);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro)) {
            exec(c, "INSERT INTO categoria(nome, tipo) VALUES ('Tornos','MAQUINA'), ('Pastilhas','ARTIGO')");
            exec(c, "INSERT INTO maquina(codigo, descricao, categoria_id) VALUES ('T01','Torno',1)");
            exec(c, "INSERT INTO artigo(codigo, descricao, categoria_id, imagem) VALUES ('A01','Pastilha',2, x'89504E47')");
            assertEquals("0", escalar(c, "SELECT COUNT(*) FROM pragma_table_info('maquina') WHERE name = 'imagem'"));
        }
        try (Database db = Database.open(ficheiro)) {
            Connection c = db.connection();
            assertEquals(String.valueOf(SchemaInitializer.VERSAO_ATUAL), escalar(c, "PRAGMA user_version"));
            assertEquals("89504E47", escalar(c, "SELECT hex(imagem) FROM artigo WHERE codigo = 'A01'"));
            assertNull(escalar(c, "SELECT imagem FROM maquina WHERE codigo = 'T01'"));
            exec(c, "UPDATE maquina SET imagem = x'FFD8' WHERE codigo = 'T01'");
            assertEquals("FFD8", escalar(c, "SELECT hex(imagem) FROM maquina WHERE codigo = 'T01'"));
        }
        assertEquals(1, copiasAntesDeMigrar(ficheiro.resolveSibling("backups"), 5).size());
    }

    private static List<String> textos(Connection c, String sql) throws SQLException {
        List<String> r = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    sb.append(i > 1 ? "|" : "").append(rs.getString(i));
                }
                r.add(sb.toString());
            }
        }
        return r;
    }

    @Test
    void fazCopiaAntesDeMigrarEUmaSo() throws Exception {
        Path ficheiro = criarBaseV1("oficina.db");
        Path pasta = ficheiro.resolveSibling("backups");
        try (Database db = Database.open(ficheiro)) {
            assertEquals(String.valueOf(SchemaInitializer.VERSAO_ATUAL), escalar(db.connection(), "PRAGMA user_version"));
        }
        List<Path> copias = copiasAntesDeMigrar(pasta, 1);
        assertEquals(1, copias.size());
        // A cópia é a base como estava: versão 1, CHECK antigos, todos os dados.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + copias.get(0))) {
            assertEquals("1", escalar(c, "PRAGMA user_version"));
            assertEquals("2", escalar(c, "SELECT COUNT(*) FROM maquina"));
            assertThrows(SQLException.class, () -> exec(c, "UPDATE maquina SET estado = 'MANUTENCAO'"));
        }
        // Abrir outra vez (já na versão 2) não migra nem copia de novo.
        try (Database db = Database.open(ficheiro)) {
            assertEquals(String.valueOf(SchemaInitializer.VERSAO_ATUAL), escalar(db.connection(), "PRAGMA user_version"));
        }
        assertEquals(1, copiasAntesDeMigrar(pasta, 1).size());
    }

    @Test
    void baseNovaNaoGeraCopiaNemMigracao() throws Exception {
        Path ficheiro = tmp.resolve("nova/oficina.db");
        try (Database db = Database.open(ficheiro)) {
            assertEquals(String.valueOf(SchemaInitializer.VERSAO_ATUAL), escalar(db.connection(), "PRAGMA user_version"));
        }
        assertFalse(Files.exists(ficheiro.resolveSibling("backups")));
    }

    @Test
    void recusaBaseDeVersaoMaisRecenteSemLheTocar() throws Exception {
        Path ficheiro = criarBaseV1("futuro.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro)) {
            exec(c, "PRAGMA user_version = 99");
        }
        SQLException e = assertThrows(SQLException.class, () -> Database.open(ficheiro));
        assertTrue(e.getMessage().contains("mais recente"), e.getMessage());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro)) {
            assertEquals("99", escalar(c, "PRAGMA user_version"));
            assertEquals("2", escalar(c, "SELECT COUNT(*) FROM maquina"));
        }
    }

    @Test
    void migracaoComDadosInconsistentesDesfazTudo() throws Exception {
        Path ficheiro = criarBaseV1("oficina.db");
        Map<String, List<String>> antes;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro)) {
            // Registo órfão (categoria inexistente), possível em bases criadas sem chaves estrangeiras ativas.
            exec(c, "INSERT INTO maquina(codigo, descricao, categoria_id) VALUES ('X','órfã',999)");
            antes = todasAsLinhas(c);
        }
        SQLException e = assertThrows(SQLException.class, () -> Database.open(ficheiro));
        assertTrue(e.getMessage().contains("Migração desfeita"), e.getMessage());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro)) {
            assertEquals("1", escalar(c, "PRAGMA user_version"));
            assertEquals(antes, todasAsLinhas(c));
            assertFalse(objetos(c).keySet().stream().anyMatch(n -> n.endsWith("_nova")));
            assertThrows(SQLException.class, () -> exec(c, "UPDATE maquina SET estado = 'MANUTENCAO'")); // CHECK antigo
        }
    }
}
