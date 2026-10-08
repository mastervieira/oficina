package pt.oficina.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import pt.oficina.dao.ArtigoDao;
import pt.oficina.dao.IntervencaoDao;
import pt.oficina.dao.MaquinaDao;
import pt.oficina.dao.MovimentoStockDao;
import pt.oficina.dao.PlanoManutencaoDao;
import pt.oficina.db.Tx;
import pt.oficina.model.Artigo;
import pt.oficina.model.Categoria;
import pt.oficina.model.ConsumoMaterial;
import pt.oficina.model.Fornecedor;
import pt.oficina.model.Intervencao;
import pt.oficina.model.Localizacao;
import pt.oficina.model.Maquina;
import pt.oficina.model.MovimentoStock;
import pt.oficina.model.PlanoManutencao;
import pt.oficina.model.Unidades;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.model.enums.TipoCategoria;
import pt.oficina.model.enums.TipoIntervencao;
import pt.oficina.model.enums.TipoMovimento;
import pt.oficina.service.FolhaImportada.LinhaImportada;
import pt.oficina.service.ResultadoImportacao.ErroImportacao;

/**
 * Importa de uma folha de cálculo. <b>Máquinas e artigos</b> casam-se pelo código (sem distinguir maiúsculas nem
 * acentos): se não existe, cria-se; se existe, atualizam-se os dados de inventário. <b>Movimentos de stock,
 * intervenções e mudanças de estado</b> são registos que só se acrescentam: cada linha é um registo novo, sobre
 * máquinas e artigos que já existem (nunca os cria).
 *
 * <ul>
 *   <li><b>Tudo ou nada:</b> numa só transação; havendo qualquer erro, não se grava nada.</li>
 *   <li><b>Simulação:</b> corre exatamente o mesmo código e desfaz no fim, por isso a pré-visualização não pode
 *       divergir do que depois se grava.</li>
 *   <li><b>Estado e stock</b> só se aplicam a registos novos (têm histórico e movimentos próprios): a máquina
 *       nova nasce ATIVO e muda para o estado indicado; o artigo novo recebe uma ENTRADA com o stock indicado.</li>
 *   <li><b>Categorias, localizações e fornecedores</b> escolhem-se pelo nome; os que não existem são criados
 *       (e aparecem no resultado, para se verem gralhas antes de gravar).</li>
 *   <li>Uma coluna ausente deixa o valor atual; uma coluna presente com a célula vazia limpa-o (nos campos opcionais).</li>
 * </ul>
 */
public final class ImportacaoService {

    private static final String MOTIVO = "Importação do Excel";
    private static final double EPS = 1e-9;

    /** Os campos reconhecíveis nos cabeçalhos, com as formas aceites (já normalizadas, ver {@link Texto#chaveCabecalho}). */
    private enum Campo {
        CODIGO("Código", "codigo", "cod", "referencia", "codigodamaquina", "codigodoartigo"),
        DESCRICAO("Descrição", "descricao", "designacao", "nome", "descricaodaintervencao"),
        N_SERIE("Nº série", "nserie", "numeroserie", "numerodeserie", "ndeserie", "serie"),
        CATEGORIA("Categoria", "categoria"),
        LOCALIZACAO("Localização", "localizacao", "local"),
        FORNECEDOR("Fornecedor", "fornecedor"),
        DATA_AQUISICAO("Data de aquisição", "dataaquisicao", "datadeaquisicao", "aquisicao"),
        ESTADO("Estado", "estado"),
        UNIDADE("Unidade", "unidade", "unid", "un"),
        STOCK_MINIMO("Stock mínimo", "stockminimo", "stockmin", "minimo"),
        STOCK("Stock atual", "stockatual", "stockinicial", "stock", "quantidade", "existencias"),
        // movimentos, intervenções e estados
        DATA("Data", "data", "datamovimento", "datadomovimento", "datainterv", "datadaintervencao", "dataestado",
                "datadoestado", "datadamudanca"),
        TIPO("Tipo", "tipo", "tipomovimento", "tipodemovimento", "tipointervencao", "tipodeintervencao"),
        QUANTIDADE("Quantidade", "quantidade", "qtd", "qtde"),
        NOTA("Nota", "nota", "notas", "observacoes", "obs"),
        PLANO("Plano", "plano", "tarefa", "planodemanutencao"),
        CUSTO("Custo", "custo", "custoeur", "valor"),
        MOTIVO("Motivo", "motivo", "justificacao");

        final String rotulo;
        final Set<String> formas;

        Campo(String rotulo, String... formas) {
            this.rotulo = rotulo;
            this.formas = Set.of(formas);
        }
    }

    private static final List<Campo> CAMPOS_MAQUINA = List.of(Campo.CODIGO, Campo.DESCRICAO, Campo.N_SERIE,
            Campo.CATEGORIA, Campo.LOCALIZACAO, Campo.FORNECEDOR, Campo.DATA_AQUISICAO, Campo.ESTADO);
    private static final List<Campo> CAMPOS_ARTIGO = List.of(Campo.CODIGO, Campo.DESCRICAO, Campo.CATEGORIA,
            Campo.LOCALIZACAO, Campo.FORNECEDOR, Campo.UNIDADE, Campo.STOCK_MINIMO, Campo.STOCK);
    private static final List<Campo> CAMPOS_MOVIMENTO = List.of(Campo.CODIGO, Campo.DATA, Campo.TIPO, Campo.QUANTIDADE,
            Campo.NOTA);
    private static final List<Campo> CAMPOS_INTERVENCAO = List.of(Campo.CODIGO, Campo.DATA, Campo.TIPO, Campo.PLANO,
            Campo.DESCRICAO, Campo.CUSTO);
    private static final List<Campo> CAMPOS_ESTADO = List.of(Campo.CODIGO, Campo.ESTADO, Campo.DATA, Campo.MOTIVO);

    private static final Set<Campo> OBRIGATORIOS_INVENTARIO = EnumSet.of(Campo.CODIGO, Campo.DESCRICAO, Campo.CATEGORIA);
    private static final Set<Campo> OBRIGATORIOS_MOVIMENTO = EnumSet.of(Campo.CODIGO, Campo.DATA, Campo.TIPO,
            Campo.QUANTIDADE);
    private static final Set<Campo> OBRIGATORIOS_INTERVENCAO = EnumSet.of(Campo.CODIGO, Campo.DATA, Campo.TIPO);
    private static final Set<Campo> OBRIGATORIOS_ESTADO = EnumSet.of(Campo.CODIGO, Campo.ESTADO);

    private final Connection c;
    private final Clock relogio;
    private final InventarioService inventario;
    private final EstadoMaquinaService estados;
    private final StockService stock;
    private final MaquinaDao maquinas;
    private final ArtigoDao artigos;
    private final MovimentoStockDao movimentos;
    private final PlanoManutencaoDao planos;
    private final IntervencaoDao intervencoes;
    private final ManutencaoService manutencao;

    public ImportacaoService(Connection c) {
        this(c, Clock.systemDefaultZone());
    }

    public ImportacaoService(Connection c, Clock relogio) {
        this.c = c;
        this.relogio = relogio;
        this.inventario = new InventarioService(c, relogio);
        this.estados = new EstadoMaquinaService(c, relogio);
        this.stock = new StockService(c, relogio);
        this.maquinas = new MaquinaDao(c);
        this.artigos = new ArtigoDao(c);
        this.movimentos = new MovimentoStockDao(c);
        this.planos = new PlanoManutencaoDao(c);
        this.intervencoes = new IntervencaoDao(c);
        this.manutencao = new ManutencaoService(c, stock, relogio);
    }

    // ---- API ---------------------------------------------------------------

    /** @param simular se verdadeiro, não grava nada: só diz o que aconteceria */
    public ResultadoImportacao importarMaquinas(FolhaImportada folha, boolean simular) throws SQLException {
        return importar(folha, simular, CAMPOS_MAQUINA, OBRIGATORIOS_INVENTARIO, this::linhasDeMaquinas);
    }

    /** @param simular se verdadeiro, não grava nada: só diz o que aconteceria */
    public ResultadoImportacao importarArtigos(FolhaImportada folha, boolean simular) throws SQLException {
        return importar(folha, simular, CAMPOS_ARTIGO, OBRIGATORIOS_INVENTARIO, this::linhasDeArtigos);
    }

    /**
     * Cada linha é um movimento novo de um artigo que já existe. As entradas registam-se antes das saídas, por isso uma
     * saída pode vir no ficheiro antes da entrada que a cobre; só falha se, no fim, o stock ficasse negativo.
     *
     * @param simular se verdadeiro, não grava nada: só diz o que aconteceria
     */
    public ResultadoImportacao importarMovimentos(FolhaImportada folha, boolean simular) throws SQLException {
        return importar(folha, simular, CAMPOS_MOVIMENTO, OBRIGATORIOS_MOVIMENTO, this::linhasDeMovimentos);
    }

    /**
     * Cada linha é uma intervenção nova de uma máquina que já existe, opcionalmente ligada a um plano do tipo dela.
     * Não regista material consumido (isso faz-se nos movimentos de stock).
     *
     * @param simular se verdadeiro, não grava nada: só diz o que aconteceria
     */
    public ResultadoImportacao importarIntervencoes(FolhaImportada folha, boolean simular) throws SQLException {
        return importar(folha, simular, CAMPOS_INTERVENCAO, OBRIGATORIOS_INTERVENCAO, this::linhasDeIntervencoes);
    }

    /**
     * Cada linha muda o estado de uma máquina que já existe (por ordem do ficheiro; sem data, é hoje; sem motivo, é
     * «Importação do Excel»). Uma máquina que já está no estado indicado conta como «sem alterações».
     *
     * @param simular se verdadeiro, não grava nada: só diz o que aconteceria
     */
    public ResultadoImportacao importarEstados(FolhaImportada folha, boolean simular) throws SQLException {
        return importar(folha, simular, CAMPOS_ESTADO, OBRIGATORIOS_ESTADO, this::linhasDeEstados);
    }

    // ---- esqueleto comum ---------------------------------------------------

    @FunctionalInterface
    private interface Processador {
        void processar(FolhaImportada folha, Mapeamento mapa, Acumulador acc, LocalDate hoje) throws SQLException;
    }

    /** Lançada para desfazer a transação (simulação ou erros) sem ser um erro de verdade. */
    private static final class Desfazer extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Desfazer() {
            super(null, null, false, false); // sem pilha: é só um sinal
        }
    }

    private ResultadoImportacao importar(FolhaImportada folhaLida, boolean simular, List<Campo> campos,
            Set<Campo> obrigatorios, Processador processador) throws SQLException {
        FolhaImportada folha = comCabecalhoLocalizado(folhaLida, campos);
        Acumulador acc = new Acumulador(folha.linhas().size());
        Mapeamento mapa = mapear(folha.cabecalhos(), campos, obrigatorios);
        mapa.erros().forEach(m -> acc.erro(0, m));
        acc.colunasIgnoradas.addAll(mapa.ignoradas());
        if (folha.linhas().isEmpty() && !acc.temErros()) {
            acc.erro(0, "O ficheiro não tem linhas de dados (só o cabeçalho, ou está vazio).");
        }
        if (acc.temErros()) {
            return acc.resultado(false);
        }
        LocalDate hoje = LocalDate.now(relogio);
        try {
            Tx.call(c, () -> {
                processador.processar(folha, mapa, acc, hoje);
                if (simular || acc.temErros()) {
                    throw new Desfazer();
                }
                return null;
            });
        } catch (Desfazer revertido) {
            // simulação ou erros: nada ficou gravado
        }
        return acc.resultado(!simular && !acc.temErros());
    }

    /** Quantas linhas, no máximo, se percorrem à procura dos cabeçalhos (pode haver um título por cima). */
    private static final int LINHAS_ATE_CABECALHO = 20;

    /**
     * Uma folha pode ter um título ("Inventário 2026") ou linhas de notas por cima da tabela. A linha de cabeçalhos é a
     * primeira com pelo menos dois campos reconhecidos; as anteriores ignoram-se. Sem nenhuma, fica como veio e os
     * erros normais dizem que colunas faltam.
     */
    private static FolhaImportada comCabecalhoLocalizado(FolhaImportada folha, List<Campo> campos) {
        if (reconhecidos(folha.cabecalhos(), campos) >= 2) {
            return folha;
        }
        List<LinhaImportada> linhas = folha.linhas();
        for (int i = 0; i < Math.min(linhas.size(), LINHAS_ATE_CABECALHO); i++) {
            List<String> candidatos = new ArrayList<>();
            for (Object v : linhas.get(i).valores()) {
                candidatos.add(v == null ? "" : v.toString());
            }
            if (reconhecidos(candidatos, campos) >= 2) {
                return new FolhaImportada(candidatos, linhas.subList(i + 1, linhas.size()));
            }
        }
        return folha;
    }

    private static long reconhecidos(List<String> cabecalhos, List<Campo> campos) {
        Set<String> formas = new HashSet<>();
        for (Campo campo : campos) {
            formas.add(Texto.chaveCabecalho(campo.rotulo));
            formas.addAll(campo.formas);
        }
        return cabecalhos.stream().filter(h -> h != null && formas.contains(Texto.chaveCabecalho(h))).count();
    }

    // ---- máquinas ----------------------------------------------------------

    private void linhasDeMaquinas(FolhaImportada folha, Mapeamento mapa, Acumulador acc, LocalDate hoje)
            throws SQLException {
        Catalogos cat = inventario.catalogos();
        Resolvedor categorias = new Resolvedor(acc.criadas("categorias"), nomes(cat.categoriasMaquina(), Categoria::nome, Categoria::id),
                nome -> inventario.criarCategoria(nome, TipoCategoria.MAQUINA).id());
        Resolvedor localizacoes = new Resolvedor(acc.criadas("localizações"), nomes(cat.localizacoes(), Localizacao::nome, Localizacao::id),
                nome -> inventario.criarLocalizacao(nome).id());
        Resolvedor fornecedores = new Resolvedor(acc.criadas("fornecedores"), nomes(cat.fornecedores(), Fornecedor::nome, Fornecedor::id),
                nome -> inventario.criarFornecedor(nome, null).id());
        Map<String, Maquina> existentes = new HashMap<>();
        for (Maquina m : maquinas.listar()) {
            existentes.put(Texto.chave(m.codigo()), m);
        }
        Set<String> vistos = new HashSet<>();
        int estadoIgnorado = 0;

        for (LinhaImportada linha : folha.linhas()) {
            try {
                Celulas v = new Celulas(mapa, linha);
                String codigo = obrigatorio(v.texto(Campo.CODIGO), "O código");
                if (!vistos.add(Texto.chave(codigo))) {
                    throw new ValidacaoException("O código " + codigo + " está repetido no ficheiro.");
                }
                String descricao = obrigatorio(v.texto(Campo.DESCRICAO), "A descrição");
                String nSerie = v.texto(Campo.N_SERIE);
                LocalDate data = v.data(Campo.DATA_AQUISICAO, "A data de aquisição");
                EstadoMaquina estado = v.estado();
                String nomeCategoria = obrigatorio(v.texto(Campo.CATEGORIA), "A categoria");
                // só agora se resolvem (e criam) as listas: uma linha inválida não deve deixar nomes criados
                Long categoria = categorias.id(nomeCategoria);
                Long localizacao = localizacoes.id(v.texto(Campo.LOCALIZACAO));
                Long fornecedor = fornecedores.id(v.texto(Campo.FORNECEDOR));

                Maquina existente = existentes.get(Texto.chave(codigo));
                if (existente == null) {
                    Maquina nova = inventario.guardarMaquina(new Maquina(null, codigo, descricao, nSerie, categoria,
                            localizacao, fornecedor, data, null));
                    if (estado != null && estado != EstadoMaquina.ATIVO) {
                        estados.mudarEstado(nova.id(), estado, hoje, MOTIVO);
                    }
                    acc.criados++;
                } else {
                    Maquina alterada = new Maquina(existente.id(), existente.codigo(), descricao,
                            v.tem(Campo.N_SERIE) ? nSerie : existente.nSerie(), categoria,
                            v.tem(Campo.LOCALIZACAO) ? localizacao : existente.localizacaoId(),
                            v.tem(Campo.FORNECEDOR) ? fornecedor : existente.fornecedorId(),
                            v.tem(Campo.DATA_AQUISICAO) ? data : existente.dataAquisicao(), existente.estado());
                    if (alterada.equals(existente)) {
                        acc.semAlteracoes++;
                    } else {
                        inventario.guardarMaquina(alterada);
                        acc.atualizados++;
                    }
                    if (estado != null && estado != existente.estado()) {
                        estadoIgnorado++;
                    }
                }
            } catch (ValidacaoException e) {
                acc.erro(linha.numero(), e.getMessage());
            }
        }
        if (estadoIgnorado > 0) {
            acc.avisos.add("O estado só se aplica a máquinas novas; nas que já existem usa-se «Alterar estado». "
                    + "Ignorado em " + estadoIgnorado + (estadoIgnorado == 1 ? " máquina." : " máquinas."));
        }
    }

    // ---- artigos -----------------------------------------------------------

    private void linhasDeArtigos(FolhaImportada folha, Mapeamento mapa, Acumulador acc, LocalDate hoje)
            throws SQLException {
        Catalogos cat = inventario.catalogos();
        Resolvedor categorias = new Resolvedor(acc.criadas("categorias"), nomes(cat.categoriasArtigo(), Categoria::nome, Categoria::id),
                nome -> inventario.criarCategoria(nome, TipoCategoria.ARTIGO).id());
        Resolvedor localizacoes = new Resolvedor(acc.criadas("localizações"), nomes(cat.localizacoes(), Localizacao::nome, Localizacao::id),
                nome -> inventario.criarLocalizacao(nome).id());
        Resolvedor fornecedores = new Resolvedor(acc.criadas("fornecedores"), nomes(cat.fornecedores(), Fornecedor::nome, Fornecedor::id),
                nome -> inventario.criarFornecedor(nome, null).id());
        Map<String, Artigo> existentes = new HashMap<>();
        for (Artigo a : artigos.listar()) {
            existentes.put(Texto.chave(a.codigo()), a);
        }
        Map<Long, Double> stocksAtuais = mapa.colunas().containsKey(Campo.STOCK) ? movimentos.stocks() : Map.of();
        Set<String> vistos = new HashSet<>();
        int stockIgnorado = 0;

        for (LinhaImportada linha : folha.linhas()) {
            try {
                Celulas v = new Celulas(mapa, linha);
                String codigo = obrigatorio(v.texto(Campo.CODIGO), "O código");
                if (!vistos.add(Texto.chave(codigo))) {
                    throw new ValidacaoException("O código " + codigo + " está repetido no ficheiro.");
                }
                String descricao = obrigatorio(v.texto(Campo.DESCRICAO), "A descrição");
                String unidade = v.unidade();
                Double minimo = v.numero(Campo.STOCK_MINIMO, "O stock mínimo");
                Double stockIndicado = v.numero(Campo.STOCK, "O stock");
                if (stockIndicado != null && stockIndicado < 0) {
                    throw new ValidacaoException("O stock não pode ser negativo.");
                }
                String nomeCategoria = obrigatorio(v.texto(Campo.CATEGORIA), "A categoria");
                Long categoria = categorias.id(nomeCategoria);
                Long localizacao = localizacoes.id(v.texto(Campo.LOCALIZACAO));
                Long fornecedor = fornecedores.id(v.texto(Campo.FORNECEDOR));

                Artigo existente = existentes.get(Texto.chave(codigo));
                if (existente == null) {
                    Artigo novo = inventario.guardarArtigo(new Artigo(null, codigo, descricao, categoria, localizacao,
                            fornecedor, unidade == null ? Unidades.PADRAO : unidade, minimo == null ? 0 : minimo));
                    if (stockIndicado != null && stockIndicado > 0) {
                        stock.registar(novo.id(), TipoMovimento.ENTRADA, hoje, stockIndicado, MOTIVO);
                    }
                    acc.criados++;
                } else {
                    Artigo alterado = new Artigo(existente.id(), existente.codigo(), descricao, categoria,
                            v.tem(Campo.LOCALIZACAO) ? localizacao : existente.localizacaoId(),
                            v.tem(Campo.FORNECEDOR) ? fornecedor : existente.fornecedorId(),
                            unidade != null ? unidade : existente.unidade(),
                            v.tem(Campo.STOCK_MINIMO) ? (minimo == null ? 0 : minimo) : existente.stockMinimo());
                    if (mesmosDados(existente, alterado)) {
                        acc.semAlteracoes++;
                    } else {
                        inventario.guardarArtigo(alterado);
                        acc.atualizados++;
                    }
                    if (stockIndicado != null
                            && Math.abs(stockIndicado - stocksAtuais.getOrDefault(existente.id(), 0.0)) > EPS) {
                        stockIgnorado++;
                    }
                }
            } catch (ValidacaoException e) {
                acc.erro(linha.numero(), e.getMessage());
            }
        }
        if (stockIgnorado > 0) {
            acc.avisos.add("O stock só se aplica a artigos novos; nos que já existem usam-se os movimentos de stock. "
                    + "Ignorado em " + stockIgnorado + (stockIgnorado == 1 ? " artigo." : " artigos."));
        }
    }

    /** O stock mínimo compara-se com tolerância (vem de uma célula numérica). */
    private static boolean mesmosDados(Artigo a, Artigo b) {
        return Objects.equals(a.descricao(), b.descricao()) && a.categoriaId() == b.categoriaId()
                && Objects.equals(a.localizacaoId(), b.localizacaoId())
                && Objects.equals(a.fornecedorId(), b.fornecedorId())
                && Objects.equals(a.unidade(), b.unidade())
                && Math.abs(a.stockMinimo() - b.stockMinimo()) < EPS;
    }

    // ---- movimentos de stock -----------------------------------------------

    /** O que identifica um movimento, para avisar de linhas que parecem já registadas. */
    private record ChaveMovimento(long artigoId, LocalDate data, TipoMovimento tipo, double quantidade, String nota) {
    }

    private record MovimentoLido(int linha, Artigo artigo, LocalDate data, TipoMovimento tipo, double quantidade,
            String nota) {
    }

    private void linhasDeMovimentos(FolhaImportada folha, Mapeamento mapa, Acumulador acc, LocalDate hoje)
            throws SQLException {
        Map<String, Artigo> porCodigo = new HashMap<>();
        for (Artigo a : artigos.listar()) {
            porCodigo.put(Texto.chave(a.codigo()), a);
        }
        Set<ChaveMovimento> registados = new HashSet<>();
        for (MovimentoStock m : movimentos.listar(null)) {
            registados.add(new ChaveMovimento(m.artigoId(), m.dataMov(), m.tipo(), m.quantidade(), m.nota()));
        }

        List<MovimentoLido> lidos = new ArrayList<>();
        for (LinhaImportada linha : folha.linhas()) {
            try {
                Celulas v = new Celulas(mapa, linha);
                String codigo = obrigatorio(v.texto(Campo.CODIGO), "O código");
                Artigo artigo = porCodigo.get(Texto.chave(codigo));
                if (artigo == null) {
                    throw new ValidacaoException("O artigo «" + codigo + "» não existe (importe-o primeiro em "
                            + "Inventário › Artigos).");
                }
                LocalDate data = obrigatorio(v.data(Campo.DATA, "A data"), "A data");
                TipoMovimento tipo = obrigatorio(v.enumerado(Campo.TIPO, TipoMovimento.class, "O tipo",
                        "Entrada ou Saída"), "O tipo");
                double quantidade = obrigatorio(v.numero(Campo.QUANTIDADE, "A quantidade"), "A quantidade");
                lidos.add(new MovimentoLido(linha.numero(), artigo, data, tipo, quantidade, v.texto(Campo.NOTA)));
            } catch (ValidacaoException e) {
                acc.erro(linha.numero(), e.getMessage());
            }
        }

        // Primeiro todas as entradas, depois as saídas (cada grupo por data): o stock só se confere pelo total, por isso
        // a ordem do ficheiro (um exportado vem do mais recente para o mais antigo) não pode causar falta de stock.
        lidos.sort(java.util.Comparator.comparing(MovimentoLido::tipo).thenComparing(MovimentoLido::data));
        int repetidos = 0;
        for (MovimentoLido m : lidos) {
            try {
                if (registados.contains(new ChaveMovimento(m.artigo().id(), m.data(), m.tipo(), m.quantidade(), m.nota()))) {
                    repetidos++;
                }
                stock.registar(m.artigo().id(), m.tipo(), m.data(), m.quantidade(), m.nota());
                acc.criados++;
            } catch (ValidacaoException e) {
                acc.erro(m.linha(), e.getMessage());
            }
        }
        acc.erros.sort(java.util.Comparator.comparingInt(ErroImportacao::linha)); // registou-se por data, lê-se por linha
        if (repetidos > 0) {
            acc.avisos.add(repetidos + (repetidos == 1 ? " linha coincide" : " linhas coincidem")
                    + " com movimentos já registados (mesmo artigo, data, tipo, quantidade e nota). Se já importou este "
                    + "ficheiro, vai duplicar: os movimentos não se apagam, só se corrigem com um movimento contrário.");
        }
    }

    // ---- intervenções ------------------------------------------------------

    private record ChaveIntervencao(long maquinaId, LocalDate data, TipoIntervencao tipo, Long planoId, String descricao,
            Double custo) {
    }

    private void linhasDeIntervencoes(FolhaImportada folha, Mapeamento mapa, Acumulador acc, LocalDate hoje)
            throws SQLException {
        Catalogos cat = inventario.catalogos();
        Map<String, Maquina> porCodigo = new HashMap<>();
        for (Maquina m : maquinas.listar()) {
            porCodigo.put(Texto.chave(m.codigo()), m);
        }
        Map<Long, Map<String, PlanoManutencao>> planosDoTipo = new HashMap<>();
        for (PlanoManutencao p : planos.listar()) {
            planosDoTipo.computeIfAbsent(p.categoriaId(), k -> new HashMap<>()).put(Texto.chave(p.tarefa()), p);
        }
        Set<ChaveIntervencao> registadas = new HashSet<>();
        for (Intervencao i : intervencoes.listar()) {
            registadas.add(new ChaveIntervencao(i.maquinaId(), i.dataInterv(), i.tipo(), i.planoId(), i.descricao(),
                    i.custo()));
        }

        int repetidas = 0;
        for (LinhaImportada linha : folha.linhas()) {
            try {
                Celulas v = new Celulas(mapa, linha);
                String codigo = obrigatorio(v.texto(Campo.CODIGO), "O código");
                Maquina maquina = porCodigo.get(Texto.chave(codigo));
                if (maquina == null) {
                    throw new ValidacaoException("A máquina «" + codigo + "» não existe (importe-a primeiro em "
                            + "Inventário › Máquinas).");
                }
                LocalDate data = obrigatorio(v.data(Campo.DATA, "A data"), "A data");
                TipoIntervencao tipo = obrigatorio(v.enumerado(Campo.TIPO, TipoIntervencao.class, "O tipo",
                        "Preventiva, Programada ou Corretiva"), "O tipo");
                String tarefa = v.texto(Campo.PLANO);
                Long planoId = null;
                if (tarefa != null) {
                    PlanoManutencao plano = planosDoTipo.getOrDefault(maquina.categoriaId(), Map.of())
                            .get(Texto.chave(tarefa));
                    if (plano == null) {
                        throw new ValidacaoException("O tipo «" + cat.categoria(maquina.categoriaId()) + "» da máquina "
                                + maquina.codigo() + " não tem o plano «" + tarefa + "» (crie-o em Configurações › "
                                + "Planos de manutenção).");
                    }
                    planoId = plano.id();
                }
                String descricao = v.texto(Campo.DESCRICAO);
                Double custo = v.numero(Campo.CUSTO, "O custo");
                if (registadas.contains(new ChaveIntervencao(maquina.id(), data, tipo, planoId, descricao, custo))) {
                    repetidas++;
                }
                manutencao.registarIntervencao(new Intervencao(null, maquina.id(), planoId, data, tipo, descricao, custo),
                        List.<ConsumoMaterial>of());
                acc.criados++;
            } catch (ValidacaoException e) {
                acc.erro(linha.numero(), e.getMessage());
            }
        }
        if (repetidas > 0) {
            acc.avisos.add(repetidas + (repetidas == 1 ? " linha coincide" : " linhas coincidem")
                    + " com intervenções já registadas (mesma máquina, data, tipo, plano, descrição e custo). Se já "
                    + "importou este ficheiro, vai duplicar.");
        }
    }

    // ---- estados -----------------------------------------------------------

    private void linhasDeEstados(FolhaImportada folha, Mapeamento mapa, Acumulador acc, LocalDate hoje)
            throws SQLException {
        Map<String, Maquina> porCodigo = new HashMap<>();
        Map<Long, EstadoMaquina> atuais = new HashMap<>();
        for (Maquina m : maquinas.listar()) {
            porCodigo.put(Texto.chave(m.codigo()), m);
            atuais.put(m.id(), m.estado());
        }
        for (LinhaImportada linha : folha.linhas()) {
            try {
                Celulas v = new Celulas(mapa, linha);
                String codigo = obrigatorio(v.texto(Campo.CODIGO), "O código");
                Maquina maquina = porCodigo.get(Texto.chave(codigo));
                if (maquina == null) {
                    throw new ValidacaoException("A máquina «" + codigo + "» não existe (importe-a primeiro em "
                            + "Inventário › Máquinas).");
                }
                EstadoMaquina novo = obrigatorio(v.estado(), "O estado");
                LocalDate data = v.data(Campo.DATA, "A data");
                String motivo = v.texto(Campo.MOTIVO);
                if (atuais.get(maquina.id()) == novo) {
                    acc.semAlteracoes++;
                } else {
                    estados.mudarEstado(maquina.id(), novo, data == null ? hoje : data, motivo == null ? MOTIVO : motivo);
                    atuais.put(maquina.id(), novo);
                    acc.criados++;
                }
            } catch (ValidacaoException e) {
                acc.erro(linha.numero(), e.getMessage());
            }
        }
    }

    // ---- cabeçalhos --------------------------------------------------------

    private record Mapeamento(Map<Campo, Integer> colunas, List<String> ignoradas, List<String> erros) {
    }

    private static Mapeamento mapear(List<String> cabecalhos, List<Campo> campos, Set<Campo> obrigatorios) {
        Map<String, Campo> porForma = new HashMap<>();
        for (Campo campo : campos) {
            porForma.put(Texto.chaveCabecalho(campo.rotulo), campo);
            campo.formas.forEach(f -> porForma.put(f, campo));
        }
        Map<Campo, Integer> colunas = new EnumMap<>(Campo.class);
        List<String> ignoradas = new ArrayList<>();
        List<String> erros = new ArrayList<>();
        for (int i = 0; i < cabecalhos.size(); i++) {
            String nome = cabecalhos.get(i) == null ? "" : cabecalhos.get(i).trim();
            if (nome.isEmpty()) {
                continue; // coluna sem título
            }
            Campo campo = porForma.get(Texto.chaveCabecalho(nome));
            if (campo == null) {
                ignoradas.add(nome);
            } else if (colunas.putIfAbsent(campo, i) != null) {
                erros.add("A coluna «" + campo.rotulo + "» aparece mais do que uma vez no ficheiro.");
            }
        }
        for (Campo obrigatorio : obrigatorios) {
            if (!colunas.containsKey(obrigatorio)) {
                erros.add("Falta a coluna obrigatória «" + obrigatorio.rotulo + "».");
            }
        }
        return new Mapeamento(colunas, ignoradas, erros);
    }

    // ---- células -----------------------------------------------------------

    /** Os valores de uma linha, lidos pelo campo (e não pela posição da coluna). */
    private static final class Celulas {
        private final Mapeamento mapa;
        private final LinhaImportada linha;

        Celulas(Mapeamento mapa, LinhaImportada linha) {
            this.mapa = mapa;
            this.linha = linha;
        }

        boolean tem(Campo campo) {
            return mapa.colunas().containsKey(campo);
        }

        Object bruto(Campo campo) {
            Integer i = mapa.colunas().get(campo);
            Object valor = i == null || i >= linha.valores().size() ? null : linha.valores().get(i);
            if (valor == FolhaImportada.FORMULA_SEM_VALOR) {
                throw new ValidacaoException("A coluna «" + campo.rotulo + "» tem uma fórmula sem valor calculado (o ficheiro "
                        + "nunca foi aberto no Excel). Abra-o no Excel, guarde-o e importe de novo.");
            }
            return valor;
        }

        /** Texto sem espaços nas pontas, ou null se vazio. Números inteiros (ex.: um código 12345) sem ".0". */
        String texto(Campo campo) {
            Object o = bruto(campo);
            if (o == null) {
                return null;
            }
            String s;
            if (o instanceof Double d) {
                s = Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15
                        ? String.valueOf((long) d.doubleValue()) : java.math.BigDecimal.valueOf(d).toPlainString();
            } else {
                s = o.toString();
            }
            s = s.replaceAll("\\s+", " ").trim();
            return s.isEmpty() ? null : s;
        }

        Double numero(Campo campo, String nome) {
            Object o = bruto(campo);
            if (o instanceof Double d) {
                if (!Double.isFinite(d)) {
                    throw new ValidacaoException(nome + " não é um número válido.");
                }
                return d;
            }
            String s = texto(campo);
            return s == null ? null : Numeros.ler(s, nome);
        }

        LocalDate data(Campo campo, String nome) {
            Object o = bruto(campo);
            if (o == null) {
                return null;
            }
            if (o instanceof LocalDate d) {
                return d;
            }
            String s = o instanceof Double ? null : texto(campo);
            if (s == null) {
                throw new ValidacaoException(nome + " não é uma data válida (use uma célula de data ou 31/12/2026).");
            }
            LocalDate d = Datas.ler(s);
            if (d != null) {
                return d;
            }
            throw new ValidacaoException(nome + " «" + s + "» não é uma data válida (use 31/12/2026 ou 2026-12-31).");
        }

        /** null se vazio; aceita o texto mostrado ("Manutenção") ou o nome interno, sem distinguir acentos. */
        EstadoMaquina estado() {
            String s = texto(Campo.ESTADO);
            if (s == null) {
                return null;
            }
            for (EstadoMaquina e : EstadoMaquina.values()) {
                if (Texto.chave(s).equals(Texto.chave(e.toString())) || Texto.chave(s).equals(Texto.chave(e.name()))) {
                    return e;
                }
            }
            throw new ValidacaoException("O estado «" + s + "» não é válido (use: Ativo, Inoperativo, Manutenção ou Abatido).");
        }

        /** null se vazio; aceita o texto mostrado ou o nome interno, sem distinguir maiúsculas nem acentos. */
        <E extends Enum<E>> E enumerado(Campo campo, Class<E> tipo, String nome, String validos) {
            String s = texto(campo);
            if (s == null) {
                return null;
            }
            for (E e : tipo.getEnumConstants()) {
                if (Texto.chave(s).equals(Texto.chave(e.toString())) || Texto.chave(s).equals(Texto.chave(e.name()))) {
                    return e;
                }
            }
            throw new ValidacaoException(nome + " «" + s + "» não é válido (use: " + validos + ").");
        }

        /** null se vazio; senão tem de ser uma das unidades da lista. */
        String unidade() {
            String s = texto(Campo.UNIDADE);
            if (s == null) {
                return null;
            }
            for (String u : Unidades.LISTA) {
                if (Texto.chave(u).equals(Texto.chave(s))) {
                    return u;
                }
            }
            throw new ValidacaoException("A unidade «" + s + "» não é válida (use: " + String.join(", ", Unidades.LISTA) + ").");
        }
    }

    private static <T> T obrigatorio(T valor, String campo) {
        if (valor == null) {
            throw ValidacaoException.obrigatorio(campo);
        }
        return valor;
    }

    // ---- listas de apoio ---------------------------------------------------

    @FunctionalInterface
    private interface CriarLista {
        long criar(String nome) throws SQLException;
    }

    private static <T> Map<String, Long> nomes(List<T> itens, java.util.function.Function<T, String> nome,
            java.util.function.Function<T, Long> id) {
        Map<String, Long> r = new HashMap<>();
        itens.forEach(i -> r.put(Texto.chave(nome.apply(i)), id.apply(i)));
        return r;
    }

    /** Dado um nome, devolve o id; se a lista ainda não o tem, cria-o (e regista-o para o relatório). */
    private static final class Resolvedor {
        private final List<String> criadas;
        private final Map<String, Long> ids;
        private final CriarLista criar;

        Resolvedor(List<String> criadas, Map<String, Long> ids, CriarLista criar) {
            this.criadas = criadas;
            this.ids = ids;
            this.criar = criar;
        }

        Long id(String nome) throws SQLException {
            if (nome == null) {
                return null;
            }
            Long id = ids.get(Texto.chave(nome));
            if (id == null) {
                id = criar.criar(nome);
                ids.put(Texto.chave(nome), id);
                criadas.add(nome);
            }
            return id;
        }
    }

    // ---- resultado ---------------------------------------------------------

    private static final class Acumulador {
        final int linhasLidas;
        int criados;
        int atualizados;
        int semAlteracoes;
        final List<ErroImportacao> erros = new ArrayList<>();
        final Map<String, List<String>> listasCriadas = new LinkedHashMap<>();
        final List<String> avisos = new ArrayList<>();
        final List<String> colunasIgnoradas = new ArrayList<>();

        Acumulador(int linhasLidas) {
            this.linhasLidas = linhasLidas;
        }

        List<String> criadas(String lista) {
            return listasCriadas.computeIfAbsent(lista, k -> new ArrayList<>());
        }

        void erro(int linha, String mensagem) {
            erros.add(new ErroImportacao(linha, mensagem));
        }

        boolean temErros() {
            return !erros.isEmpty();
        }

        ResultadoImportacao resultado(boolean aplicada) {
            Map<String, List<String>> criadas = new LinkedHashMap<>();
            listasCriadas.forEach((k, v) -> {
                if (!v.isEmpty()) {
                    criadas.put(k, List.copyOf(v));
                }
            });
            return new ResultadoImportacao(linhasLidas, criados, atualizados, semAlteracoes, List.copyOf(erros),
                    criadas, List.copyOf(avisos), List.copyOf(colunasIgnoradas), aplicada);
        }
    }
}
