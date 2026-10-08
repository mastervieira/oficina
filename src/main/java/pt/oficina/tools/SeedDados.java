package pt.oficina.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import pt.oficina.db.Backups;
import pt.oficina.db.Database;
import pt.oficina.db.Tx;
import pt.oficina.model.Artigo;
import pt.oficina.model.Categoria;
import pt.oficina.model.ConsumoMaterial;
import pt.oficina.model.Fornecedor;
import pt.oficina.model.Intervencao;
import pt.oficina.model.Localizacao;
import pt.oficina.model.Maquina;
import pt.oficina.model.PlanoManutencao;
import pt.oficina.model.StockArtigo;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.model.enums.TipoCategoria;
import pt.oficina.model.enums.TipoIntervencao;
import pt.oficina.model.enums.TipoMovimento;
import pt.oficina.service.EstadoMaquinaService;
import pt.oficina.service.InventarioService;
import pt.oficina.service.ManutencaoService;
import pt.oficina.service.StockService;

/**
 * Popula a base de dados da aplicação (por defeito a da pasta de dados do utilizador) com dados fictícios para desenvolvimento: máquinas
 * em todos os estados, localizações do tipo "Móvel A · Prateleira 1 · Secção C" e muitos artigos, com fornecedores,
 * movimentos de stock, planos e intervenções.
 *
 * <p>Usa os serviços da aplicação (e não SQL direto), por isso os dados cumprem as mesmas regras: histórico de
 * estados coerente, stock calculado pelos movimentos, sem datas futuras. É reprodutível: a mesma semente dá os
 * mesmos dados.
 *
 * <p>Segurança: só escreve numa base vazia ou inexistente; se tiver dados, recusa. Com {@code --recriar} a base atual
 * não se apaga: é movida para a pasta de cópias ("antes-do-seed-....db") e, se a geração falhar, é reposta.
 *
 * <p>Uso: {@code SeedDados [ficheiro.db] [--recriar] [--semente N] [--maquinas N] [--localizacoes N] [--artigos N]}
 */
public final class SeedDados {

    /** Tamanho e semente do seed. {@code hoje} é a data de referência (os dados vão até esse dia). */
    public record Parametros(int maquinas, int localizacoes, int artigos, long semente, LocalDate hoje) {

        public static Parametros padrao() {
            return new Parametros(100, 500, 10_000, 42L, LocalDate.now());
        }
    }

    public record Resumo(int maquinas, Map<EstadoMaquina, Integer> porEstado, int localizacoes, int fornecedores,
            int categorias, int artigos, int artigosAbaixoDoMinimo, int movimentos, int planos, int intervencoes) {
    }

    private static final int MAX_LOCALIZACOES = 1300; // 26 móveis x 10 prateleiras x 5 secções
    private static final String[] MARCAS = {"Alfa", "Boreal", "Corvo", "Delta", "Ébano", "Fénix"};
    private static final String[] CIDADES = {"Aveiro", "Braga", "Coimbra", "Évora", "Faro", "Guimarães", "Leiria",
        "Setúbal", "Viseu", "Viana"};
    private static final String[] FORNECEDORES = {"Metalomecânica do Vouga, Lda", "Ferramentas Atlântico, S.A.",
        "Corte & Precisão, Lda", "Lubrificantes do Norte, Lda", "Abrasivos Ibéricos, S.A.",
        "Parafusos e Fixações Lusos, Lda", "Rolamentos Central, Lda", "Equipamentos Industriais Tejo, S.A.",
        "Aços e Ligas do Sul, Lda", "Metrologia Portuguesa, Lda", "Segurança Total EPI, Lda",
        "Distribuição Técnica Beira, Lda", "Ferragens Industriais Porto, Lda", "CNC Peças & Serviços, Lda",
        "Pneumática e Hidráulica Minho, Lda", "Tecnologia de Corte Algarve, Lda", "Revestimentos Duros, S.A.",
        "Maquinar Ibéria, Lda", "Fixamar, Lda", "Ferramentas Douro, Lda"};

    /** Tipo de máquina: nome da categoria, nome da máquina e peso na distribuição. */
    private record TipoMaquina(String categoria, String nome, int peso) {
    }

    private static final List<TipoMaquina> TIPOS_MAQUINA = List.of(
            new TipoMaquina("Tornos CNC", "Torno CNC", 20),
            new TipoMaquina("Tornos paralelos", "Torno paralelo", 15),
            new TipoMaquina("Fresadoras CNC", "Fresadora CNC", 20),
            new TipoMaquina("Fresadoras convencionais", "Fresadora convencional", 12),
            new TipoMaquina("Centros de maquinação", "Centro de maquinação", 10),
            new TipoMaquina("Retificadoras", "Retificadora", 8),
            new TipoMaquina("Furadoras de coluna", "Furadora de coluna", 8),
            new TipoMaquina("Serras de fita", "Serra de fita", 7));

    private record TarefaPlano(String tarefa, int meses) {
    }

    private static final List<TarefaPlano> TAREFAS = List.of(
            new TarefaPlano("Verificação do nível de óleo", 1), new TarefaPlano("Limpeza do refrigerante", 1),
            new TarefaPlano("Lubrificação geral", 3), new TarefaPlano("Verificação de guias e fusos", 6),
            new TarefaPlano("Substituição de filtros", 6), new TarefaPlano("Inspeção elétrica", 12),
            new TarefaPlano("Nivelamento e calibração", 12), new TarefaPlano("Revisão do sistema hidráulico", 12));

    private static final String[] AVARIAS = {"Fuso avariado", "Falha no variador", "Rolamento do veio gasto",
        "Fuga de óleo hidráulico", "Erro de encoder", "Motor sobreaquecido", "Falha no sistema de refrigeração",
        "Folga excessiva nas guias"};

    private SeedDados() {
    }

    // ---- linha de comandos -----------------------------------------------

    /** Argumentos já interpretados. {@code ficheiro} é null se não foi indicado (usa-se a base da aplicação). */
    record Argumentos(Path ficheiro, Parametros parametros, boolean recriar, boolean ajuda) {
    }

    /** Resultado de {@link #executar}: o resumo e, se a base anterior foi guardada, onde ficou. */
    public record Resultado(Resumo resumo, Path copiaDoAnterior) {
    }

    /** A geração propriamente dita; existe como interface para os testes poderem simular uma falha. */
    @FunctionalInterface
    interface Gerar {
        Resumo gerar(Connection c, Parametros p) throws SQLException;
    }

    private static final String USO = """
            Uso: SeedDados [ficheiro.db] [opções]
              Sem ficheiro, usa a base de dados da aplicação (na pasta de dados do utilizador).
              Só escreve numa base vazia ou inexistente; se já tiver dados, recusa e não altera nada.
            Opções:
              --recriar           guarda a base atual em backups/antes-do-seed-....db (ao lado da base) e gera tudo de novo
              --semente N         a mesma semente dá sempre os mesmos dados (por defeito 42)
              --maquinas N        por defeito 100 (mínimo 4, uma por estado)
              --localizacoes N    por defeito 500 (máximo 1300)
              --artigos N         por defeito 10000
              --ajuda
            """;

    static Argumentos analisar(String[] args) {
        Path ficheiro = null;
        boolean recriar = false;
        boolean ajuda = false;
        Parametros p = Parametros.padrao();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--recriar" -> recriar = true;
                case "--ajuda", "-h" -> ajuda = true;
                case "--semente", "--maquinas", "--localizacoes", "--artigos" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Falta o valor de " + a + ".");
                    }
                    long valor = numero(a, args[++i]);
                    p = switch (a) {
                        case "--semente" -> new Parametros(p.maquinas(), p.localizacoes(), p.artigos(), valor, p.hoje());
                        case "--maquinas" -> new Parametros((int) valor, p.localizacoes(), p.artigos(), p.semente(), p.hoje());
                        case "--localizacoes" -> new Parametros(p.maquinas(), (int) valor, p.artigos(), p.semente(), p.hoje());
                        default -> new Parametros(p.maquinas(), p.localizacoes(), (int) valor, p.semente(), p.hoje());
                    };
                }
                default -> {
                    if (a.startsWith("--")) {
                        throw new IllegalArgumentException("Opção desconhecida: " + a);
                    }
                    if (ficheiro != null) {
                        throw new IllegalArgumentException("Só pode indicar um ficheiro (recebi também " + a + ").");
                    }
                    ficheiro = Path.of(a);
                }
            }
        }
        return new Argumentos(ficheiro, p, recriar, ajuda);
    }

    private static long numero(String opcao, String texto) {
        try {
            long v = Long.parseLong(texto);
            if (!"--semente".equals(opcao) && (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("O valor de " + opcao + " tem de ser um número inteiro (recebi \"" + texto + "\").");
        }
    }

    public static void main(String[] args) {
        Argumentos a;
        try {
            a = analisar(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage() + "\n");
            System.err.print(USO);
            System.exit(2);
            return;
        }
        if (a.ajuda()) {
            System.out.print(USO);
            return;
        }
        Path ficheiro = (a.ficheiro() != null ? a.ficheiro() : Database.defaultPath()).toAbsolutePath().normalize();
        long inicio = System.nanoTime();
        try {
            Resultado r = executar(ficheiro, a.parametros(), a.recriar());
            imprimir(r, ficheiro, (System.nanoTime() - inicio) / 1_000_000);
        } catch (IllegalArgumentException | IllegalStateException e) { // pedido recusado: nada foi alterado
            System.err.println(e.getMessage());
            System.exit(2);
        } catch (SQLException | IOException | RuntimeException e) {
            e.printStackTrace();
            System.err.println("Falhou: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Popula a base em {@code ficheiro}. Se não existir, cria-a. Se existir e estiver vazia, usa-a. Se tiver dados,
     * recusa ({@link IllegalStateException}) a menos que {@code recriar}: nesse caso guarda-a à parte e gera uma nova.
     * Se a geração falhar, a base fica como estava (ou, com {@code recriar}, a original volta ao seu lugar).
     */
    public static Resultado executar(Path ficheiro, Parametros p, boolean recriar) throws SQLException, IOException {
        return executar(ficheiro, p, recriar, SeedDados::gerar);
    }

    static Resultado executar(Path ficheiro, Parametros p, boolean recriar, Gerar gerar)
            throws SQLException, IOException {
        validar(p); // antes de mexer em qualquer ficheiro
        Path abs = ficheiro.toAbsolutePath().normalize();
        if (Files.isDirectory(abs)) {
            throw new IllegalArgumentException("É uma pasta, não um ficheiro de base de dados: " + abs);
        }
        boolean existia = Files.exists(abs);
        Path anterior = null;
        if (existia && recriar) {
            anterior = Backups.guardarAntesDeSubstituir(abs, "antes-do-seed", LocalDateTime.now());
        } else if (existia) {
            exigirVazia(abs);
        }
        boolean ficheiroNovo = !existia || recriar; // criado por esta execução (não pode ficar a meio)
        try (Database db = Database.open(abs)) {
            return new Resultado(gerar.gerar(db.connection(), p), anterior);
        } catch (SQLException | RuntimeException | Error e) {
            // A geração é uma só transação: numa base que já existia vazia, nada ficou. Num ficheiro criado agora
            // apaga-se o que sobrou, e a base que estava antes volta ao seu lugar.
            try {
                if (ficheiroNovo) {
                    apagarSobras(abs);
                }
                if (anterior != null) {
                    Backups.repor(anterior, abs);
                }
            } catch (IOException ioe) {
                e.addSuppressed(ioe);
            }
            throw e;
        }
    }

    private static final List<String> TABELAS = List.of("categoria", "localizacao", "fornecedor", "maquina", "artigo",
            "movimento_stock", "plano_manutencao", "intervencao", "historico_estado");

    /** Recusa se o ficheiro for uma base com dados (ou não for uma base legível). Um ficheiro vazio conta como vazio. */
    private static void exigirVazia(Path ficheiro) throws IOException {
        if (Files.size(ficheiro) == 0) {
            return; // o SQLite trata um ficheiro vazio como uma base nova
        }
        Map<String, Integer> comDados = new java.util.LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ficheiro); Statement st = c.createStatement()) {
            st.execute("PRAGMA query_only = ON"); // é só uma verificação
            for (String tabela : TABELAS) {
                if (existeTabela(c, tabela)) {
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tabela)) {
                        rs.next();
                        if (rs.getInt(1) > 0) {
                            comDados.put(tabela, rs.getInt(1));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("O ficheiro " + ficheiro + " existe mas não é uma base de dados SQLite legível ("
                    + e.getMessage() + "). Nada foi alterado.\nCom --recriar guarda-o à parte (em "
                    + Backups.pastaDe(ficheiro) + ") e cria uma base nova.", e);
        }
        if (!comDados.isEmpty()) {
            throw new IllegalStateException("A base de dados " + ficheiro + " já tem dados (" + descrever(comDados)
                    + "). Nada foi alterado.\nPara a substituir use --recriar: a atual fica guardada em "
                    + Backups.pastaDe(ficheiro) + " e pode ser recuperada.");
        }
    }

    private static boolean existeTabela(Connection c, String tabela) throws SQLException {
        try (var ps = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, tabela);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String descrever(Map<String, Integer> contagens) {
        List<String> partes = new ArrayList<>();
        contagens.forEach((tabela, n) -> partes.add(n + " " + switch (tabela) {
            case "categoria" -> "categorias";
            case "localizacao" -> "localizações";
            case "fornecedor" -> "fornecedores";
            case "maquina" -> "máquinas";
            case "artigo" -> "artigos";
            case "movimento_stock" -> "movimentos de stock";
            case "plano_manutencao" -> "planos";
            case "intervencao" -> "intervenções";
            default -> "linhas de histórico";
        }));
        return String.join(", ", partes);
    }

    private static void apagarSobras(Path ficheiro) throws IOException {
        for (String sufixo : new String[] {"", "-journal", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(ficheiro + sufixo));
        }
    }

    private static void imprimir(Resultado resultado, Path ficheiro, long ms) {
        Resumo r = resultado.resumo();
        System.out.println("Base de dados populada em " + ms / 1000.0 + " s:");
        System.out.println("  " + ficheiro);
        System.out.printf("  máquinas ......... %,d  %s%n", r.maquinas(), r.porEstado());
        System.out.printf("  localizações ..... %,d%n", r.localizacoes());
        System.out.printf("  fornecedores ..... %,d%n", r.fornecedores());
        System.out.printf("  categorias ....... %,d%n", r.categorias());
        System.out.printf("  artigos .......... %,d  (%,d abaixo do stock mínimo)%n", r.artigos(), r.artigosAbaixoDoMinimo());
        System.out.printf("  mov. de stock .... %,d%n", r.movimentos());
        System.out.printf("  planos ........... %,d%n", r.planos());
        System.out.printf("  intervenções ..... %,d%n", r.intervencoes());
        if (resultado.copiaDoAnterior() != null) {
            System.out.println("A base anterior ficou guardada em:\n  " + resultado.copiaDoAnterior());
        }
        boolean porDefeito = ficheiro.equals(Database.defaultPath().normalize());
        System.out.println("Para abrir a aplicação:  java --enable-native-access=ALL-UNNAMED "
                + (porDefeito ? "" : "-Doficina.db=" + ficheiro + " ") + "-jar target/oficina.jar");
    }

    // ---- geração ---------------------------------------------------------

    static void validar(Parametros p) {
        if (p.maquinas() < 4) {
            throw new IllegalArgumentException("São precisas pelo menos 4 máquinas (uma por estado).");
        }
        if (p.localizacoes() < 1 || p.localizacoes() > MAX_LOCALIZACOES) {
            throw new IllegalArgumentException("As localizações têm de estar entre 1 e " + MAX_LOCALIZACOES + ".");
        }
        if (p.artigos() < 0) {
            throw new IllegalArgumentException("O número de artigos não pode ser negativo.");
        }
    }

    /** Preenche uma base de dados vazia, numa única transação (se algo falhar, não fica nada). */
    public static Resumo gerar(Connection c, Parametros p) throws SQLException {
        validar(p);
        Resumo[] resultado = new Resumo[1];
        Tx.run(c, () -> resultado[0] = new Gerador(c, p).executar());
        return resultado[0];
    }

    /** Estado mutável de uma geração (tudo o que depende do gerador aleatório fica aqui). */
    private static final class Gerador {

        private final Connection c;
        private final Parametros p;
        private final Random rnd;
        private final LocalDate hoje;
        private final InventarioService inventario;
        private final List<Fornecedor> fornecedores = new ArrayList<>();
        private final List<Localizacao> localizacoes = new ArrayList<>();
        private final List<Categoria> categoriasMaquina = new ArrayList<>();
        /** Os planos de manutenção de cada tipo de máquina (pela mesma ordem de {@code categoriasMaquina}). */
        private final List<List<PlanoManutencao>> planosPorTipo = new ArrayList<>();
        /** Artigos que ainda têm stock para consumir em intervenções (id -> stock restante). */
        private final Map<Long, Double> consumiveis = new java.util.LinkedHashMap<>();
        private int planos;
        private int intervencoes;

        Gerador(Connection c, Parametros p) {
            this.c = c;
            this.p = p;
            this.rnd = new Random(p.semente());
            this.hoje = p.hoje();
            this.inventario = new InventarioService(c, relogio(hoje));
        }

        Resumo executar() throws SQLException {
            criarFornecedores();
            criarLocalizacoes();
            criarArtigos();
            criarMaquinas();
            return resumo();
        }

        // -- listas de apoio

        private void criarFornecedores() throws SQLException {
            int n = FORNECEDORES.length + CIDADES.length;
            for (int i = 0; i < n; i++) {
                String nome = i < FORNECEDORES.length ? FORNECEDORES[i]
                        : "Industrial " + CIDADES[i - FORNECEDORES.length] + ", Lda";
                String contacto = String.format("comercial@fornecedor%02d.example · tel. 000 000 %03d", i + 1, i + 1);
                fornecedores.add(inventario.criarFornecedor(nome, contacto));
            }
        }

        private void criarLocalizacoes() throws SQLException {
            for (int i = 0; i < p.localizacoes(); i++) {
                char movel = (char) ('A' + i / 50);
                int prateleira = 1 + (i / 5) % 10;
                char seccao = (char) ('A' + i % 5);
                localizacoes.add(inventario.criarLocalizacao(
                        "Móvel " + movel + " · Prateleira " + prateleira + " · Secção " + seccao));
            }
        }

        // -- artigos e stock

        private void criarArtigos() throws SQLException {
            int[] contagens = SeedArtigos.contagens(p.artigos());
            for (int f = 0; f < contagens.length; f++) {
                SeedArtigos.Familia familia = SeedArtigos.FAMILIAS.get(f);
                if (contagens[f] == 0) {
                    continue;
                }
                Categoria categoria = inventario.criarCategoria(familia.categoria(), familia.tipo());
                for (int i = 0; i < contagens[f]; i++) {
                    int minimo = rnd.nextInt(10) == 0 ? 0 : familia.minLo() + rnd.nextInt(familia.minHi() - familia.minLo() + 1);
                    Artigo a = inventario.guardarArtigo(new Artigo(null, familia.codigo(i), familia.descricao(i),
                            categoria.id(), talvez(localizacoes, 90), talvez(fornecedores, 90), familia.unidade(), minimo));
                    criarMovimentos(a, familia);
                }
            }
        }

        /**
         * Entrada inicial (há mais de 400 dias, antes de qualquer intervenção que consuma material) seguida de
         * saídas. O stock final é escolhido: ~10% abaixo do mínimo, ~5% sem stock, os restantes acima.
         */
        private void criarMovimentos(Artigo a, SeedArtigos.Familia familia) throws SQLException {
            int minimo = (int) a.stockMinimo();
            double sorteio = rnd.nextDouble();
            int alvo;
            if (minimo > 0 && sorteio < 0.10) {
                alvo = (int) Math.floor(minimo * rnd.nextDouble() * 0.9);
            } else if (sorteio < 0.15) {
                alvo = 0;
            } else {
                alvo = minimo + 1 + rnd.nextInt(Math.max(1, minimo * 3 + 10));
            }
            int saidas = rnd.nextInt(alvo / 2 + 6);
            int entrada = alvo + saidas;
            if (entrada == 0) {
                return; // artigo sem movimentos
            }
            LocalDate data = hoje.minusDays(401 + rnd.nextInt(700));
            String origem = fornecedores.get(rnd.nextInt(fornecedores.size())).nome();
            registar(a.id(), TipoMovimento.ENTRADA, data, entrada, "Compra a " + origem);
            int partes = saidas == 0 ? 0 : 1 + rnd.nextInt(Math.min(3, saidas));
            int porSair = saidas;
            for (int k = 0; k < partes; k++) {
                int qtd = k == partes - 1 ? porSair : 1 + rnd.nextInt(Math.max(1, porSair - (partes - 1 - k)));
                porSair -= qtd;
                data = entre(data, hoje.minusDays(1));
                registar(a.id(), TipoMovimento.SAIDA, data, qtd, "Consumo em produção");
            }
            if (alvo >= 5) {
                consumiveis.put(a.id(), (double) alvo);
            }
        }

        private void registar(long artigoId, TipoMovimento tipo, LocalDate data, int quantidade, String nota)
                throws SQLException {
            new StockService(c, relogio(data)).registar(artigoId, tipo, data, quantidade, nota);
        }

        // -- máquinas

        private void criarMaquinas() throws SQLException {
            List<EstadoMaquina> estados = distribuirEstados();
            for (TipoMaquina t : TIPOS_MAQUINA) {
                categoriasMaquina.add(inventario.criarCategoria(t.categoria(), TipoCategoria.MAQUINA));
            }
            criarPlanosDosTipos();
            for (int i = 0; i < p.maquinas(); i++) {
                int tipo = sortearTipoMaquina();
                String modelo = MARCAS[rnd.nextInt(MARCAS.length)] + " " + (char) ('A' + rnd.nextInt(6)) + "X-"
                        + (100 + rnd.nextInt(900));
                LocalDate aquisicao = hoje.minusDays(60 + rnd.nextInt(365 * 20));
                // Clock na data de aquisição: o "Registo inicial" do histórico fica nessa data.
                Maquina m = new InventarioService(c, relogio(aquisicao)).guardarMaquina(new Maquina(null,
                        String.format("MAQ-%03d", i + 1), TIPOS_MAQUINA.get(tipo).nome() + " " + modelo,
                        rnd.nextInt(10) < 8 ? "SN" + (100000 + rnd.nextInt(900000)) : null,
                        categoriasMaquina.get(tipo).id(), talvez(localizacoes, 85), talvez(fornecedores, 90),
                        aquisicao, null));
                LocalDate fim = historicoDeEstados(m, aquisicao, estados.get(i));
                criarManutencao(m, planosPorTipo.get(tipo), aquisicao, fim);
            }
        }

        /** 55% ativas, 20% inoperativas, 15% em manutenção, 10% abatidas (pelo menos uma de cada). */
        private List<EstadoMaquina> distribuirEstados() {
            int n = p.maquinas();
            int abatidas = Math.max(1, Math.round(n * 0.10f));
            int manutencao = Math.max(1, Math.round(n * 0.15f));
            int inoperativas = Math.max(1, Math.round(n * 0.20f));
            List<EstadoMaquina> r = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                r.add(i < abatidas ? EstadoMaquina.ABATIDO
                        : i < abatidas + manutencao ? EstadoMaquina.MANUTENCAO
                        : i < abatidas + manutencao + inoperativas ? EstadoMaquina.INOPERATIVO
                        : EstadoMaquina.ATIVO);
            }
            Collections.shuffle(r, rnd);
            return r;
        }

        private int sortearTipoMaquina() {
            int total = TIPOS_MAQUINA.stream().mapToInt(TipoMaquina::peso).sum();
            int x = rnd.nextInt(total);
            for (int i = 0; i < TIPOS_MAQUINA.size(); i++) {
                x -= TIPOS_MAQUINA.get(i).peso();
                if (x < 0) {
                    return i;
                }
            }
            return TIPOS_MAQUINA.size() - 1;
        }

        /**
         * Leva a máquina do estado inicial (ATIVO, na data de aquisição) até {@code alvo}, com datas crescentes.
         *
         * @return a data a partir da qual deixa de haver manutenção (a do abate, ou hoje)
         */
        private LocalDate historicoDeEstados(Maquina m, LocalDate aquisicao, EstadoMaquina alvo) throws SQLException {
            LocalDate data = aquisicao;
            switch (alvo) {
                case ATIVO -> {
                    if (rnd.nextInt(10) < 3) { // já esteve parada e foi reparada
                        data = mudar(m, EstadoMaquina.INOPERATIVO, data, AVARIAS[rnd.nextInt(AVARIAS.length)]);
                        mudar(m, EstadoMaquina.ATIVO, entre(data, hoje), "Reparação concluída");
                    }
                    return hoje;
                }
                case INOPERATIVO -> mudar(m, EstadoMaquina.INOPERATIVO, data, AVARIAS[rnd.nextInt(AVARIAS.length)]);
                case MANUTENCAO -> mudar(m, EstadoMaquina.MANUTENCAO, data, "Revisão geral programada");
                case ABATIDO -> {
                    if (rnd.nextBoolean()) {
                        data = mudar(m, EstadoMaquina.INOPERATIVO, data, AVARIAS[rnd.nextInt(AVARIAS.length)]);
                    }
                    return mudar(m, EstadoMaquina.ABATIDO, data, "Fim de vida útil / reparação inviável");
                }
                default -> throw new IllegalStateException("Estado inesperado: " + alvo);
            }
            return hoje;
        }

        /** Muda o estado numa data entre {@code apos} e hoje; devolve a data usada. */
        private LocalDate mudar(Maquina m, EstadoMaquina estado, LocalDate apos, String motivo) throws SQLException {
            LocalDate data = entre(apos, hoje);
            new EstadoMaquinaService(c, relogio(data)).mudarEstado(m.id(), estado, data, motivo);
            return data;
        }

        // -- manutenção

        /** Cada tipo de máquina tem 3 a 5 planos (os planos definem-se por tipo, não por máquina). */
        private void criarPlanosDosTipos() throws SQLException {
            ManutencaoService manutencao = new ManutencaoService(c, new StockService(c, relogio(hoje)), relogio(hoje));
            for (Categoria tipo : categoriasMaquina) {
                List<TarefaPlano> escolhidas = new ArrayList<>(TAREFAS);
                Collections.shuffle(escolhidas, rnd);
                List<PlanoManutencao> doTipo = new ArrayList<>();
                for (TarefaPlano t : escolhidas.subList(0, 3 + rnd.nextInt(3))) {
                    doTipo.add(manutencao.guardarPlano(new PlanoManutencao(null, tipo.id(), t.tarefa(), t.meses())));
                    planos++;
                }
                planosPorTipo.add(doTipo);
            }
        }

        /**
         * Intervenções preventivas/programadas sobre os planos do tipo da máquina (e avarias já corrigidas), até à
         * data {@code fim}. Alguns planos ficam sem intervenção desta máquina: contam como vencidos.
         */
        private void criarManutencao(Maquina m, List<PlanoManutencao> doTipo, LocalDate aquisicao, LocalDate fim)
                throws SQLException {
            StockService stock = new StockService(c, relogio(hoje));
            ManutencaoService manutencao = new ManutencaoService(c, stock, relogio(hoje));
            for (PlanoManutencao plano : doTipo) {
                if (rnd.nextInt(10) < 2) {
                    continue; // sem nenhuma intervenção desta máquina: vencido
                }
                // A última intervenção fica entre "agora" e um período e meio atrás: umas vencidas, outras a vencer.
                LocalDate data = fim.minusDays(rnd.nextInt(plano.periodicidadeMeses() * 45 + 1));
                for (int k = 1 + rnd.nextInt(3); k > 0 && !data.isBefore(aquisicao); k--) {
                    TipoIntervencao tipo = rnd.nextInt(4) == 0 ? TipoIntervencao.PROGRAMADA : TipoIntervencao.PREVENTIVA;
                    registarIntervencao(manutencao, new Intervencao(null, m.id(), plano.id(), data, tipo,
                            "Manutenção periódica: " + plano.tarefa(), rnd.nextInt(10) < 7 ? 20.0 + rnd.nextInt(380) : null));
                    data = data.minusMonths(plano.periodicidadeMeses());
                }
            }
            if (rnd.nextInt(10) < 4 && fim.isAfter(aquisicao)) { // avarias já reparadas
                for (int k = 1 + rnd.nextInt(2); k > 0; k--) {
                    registarIntervencao(manutencao, new Intervencao(null, m.id(), null, entre(aquisicao, fim),
                            TipoIntervencao.CORRETIVA, "Reparação: " + AVARIAS[rnd.nextInt(AVARIAS.length)],
                            50.0 + rnd.nextInt(2450)));
                }
            }
        }

        private void registarIntervencao(ManutencaoService manutencao, Intervencao i) throws SQLException {
            List<ConsumoMaterial> material = List.of();
            // Só se consome material de intervenções do último ano: os artigos têm entradas mais antigas que isso.
            if (!i.dataInterv().isBefore(hoje.minusDays(365)) && !consumiveis.isEmpty() && rnd.nextInt(10) < 3) {
                List<Long> ids = new ArrayList<>(consumiveis.keySet());
                long artigo = ids.get(rnd.nextInt(ids.size()));
                int qtd = 1 + rnd.nextInt(2);
                if (consumiveis.get(artigo) >= qtd) {
                    consumiveis.merge(artigo, (double) -qtd, Double::sum);
                    material = List.of(new ConsumoMaterial(artigo, qtd));
                }
            }
            manutencao.registarIntervencao(i, material);
            intervencoes++;
        }

        // -- utilitários

        private Long talvez(List<?> itens, int percentagem) {
            if (itens.isEmpty() || rnd.nextInt(100) >= percentagem) {
                return null;
            }
            Object o = itens.get(rnd.nextInt(itens.size()));
            return o instanceof Localizacao l ? l.id() : ((Fornecedor) o).id();
        }

        /** Data aleatória entre {@code de} e {@code ate}, inclusive (se {@code de} for posterior, devolve {@code ate}). */
        private LocalDate entre(LocalDate de, LocalDate ate) {
            long dias = ChronoUnit.DAYS.between(de, ate);
            return dias <= 0 ? (de.isAfter(ate) ? ate : de) : de.plusDays(rnd.nextInt((int) dias + 1));
        }

        private Resumo resumo() throws SQLException {
            Map<EstadoMaquina, Integer> porEstado = new EnumMap<>(EstadoMaquina.class);
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT estado, COUNT(*) FROM maquina GROUP BY estado")) {
                while (rs.next()) {
                    porEstado.put(EstadoMaquina.valueOf(rs.getString(1)), rs.getInt(2));
                }
            }
            int abaixo = (int) new StockService(c, relogio(hoje)).stockAtual().stream()
                    .filter(StockArtigo::abaixoDoMinimo).count();
            return new Resumo(contar("maquina"), porEstado, contar("localizacao"), contar("fornecedor"),
                    contar("categoria"), contar("artigo"), abaixo, contar("movimento_stock"), planos, intervencoes);
        }

        private int contar(String tabela) throws SQLException {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tabela)) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Relógio fixo ao meio-dia de {@code dia}: os serviços validam as datas contra este "hoje". */
    private static Clock relogio(LocalDate dia) {
        ZoneId zona = ZoneId.systemDefault();
        return Clock.fixed(dia.atTime(12, 0).atZone(zona).toInstant(), zona);
    }
}
