package pt.oficina.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.oficina.db.Database;
import pt.oficina.model.Artigo;
import pt.oficina.model.HistoricoEstado;
import pt.oficina.model.Maquina;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.model.enums.TipoCategoria;
import pt.oficina.model.enums.TipoMovimento;
import pt.oficina.service.FolhaImportada.LinhaImportada;

class ImportacaoServiceTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 5);

    private Database db;
    private InventarioService inv;
    private StockService stock;
    private EstadoMaquinaService estados;
    private ImportacaoService imp;

    @BeforeEach
    void preparar() throws SQLException {
        db = Database.inMemory();
        inv = new InventarioService(db.connection(), Relogios.em(HOJE));
        stock = new StockService(db.connection(), Relogios.em(HOJE));
        estados = new EstadoMaquinaService(db.connection(), Relogios.em(HOJE));
        imp = new ImportacaoService(db.connection(), Relogios.em(HOJE));
    }

    @AfterEach
    void fechar() throws SQLException {
        db.close();
    }

    // ---- utilitários -------------------------------------------------------

    /** Folha com a 1.ª linha de dados na linha 2 do ficheiro (a 1 é o cabeçalho). */
    private static FolhaImportada folha(List<String> cabecalhos, Object[]... linhas) {
        List<LinhaImportada> ls = new ArrayList<>();
        for (int i = 0; i < linhas.length; i++) {
            ls.add(new LinhaImportada(i + 2, Arrays.asList(linhas[i])));
        }
        return new FolhaImportada(cabecalhos, ls);
    }

    private static Object[] l(Object... valores) {
        return valores;
    }

    private static final List<String> CAB_MAQ = List.of("Código", "Descrição", "Nº série", "Categoria", "Localização",
            "Fornecedor", "Data de aquisição", "Estado");
    private static final List<String> CAB_ART = List.of("Código", "Descrição", "Categoria", "Localização", "Fornecedor",
            "Unidade", "Stock mínimo", "Stock atual");

    private int contar(String sql) throws SQLException {
        try (Statement st = db.connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private Maquina maquina(String codigo) throws SQLException {
        return inv.maquinas().stream().filter(m -> m.codigo().equalsIgnoreCase(codigo)).findFirst().orElseThrow();
    }

    private Artigo artigo(String codigo) throws SQLException {
        return inv.artigos().stream().filter(a -> a.codigo().equalsIgnoreCase(codigo)).findFirst().orElseThrow();
    }

    private List<String> mensagens(ResultadoImportacao r) {
        return r.erros().stream().map(e -> e.linha() + ": " + e.mensagem()).collect(Collectors.toList());
    }

    // ---- máquinas: criar ---------------------------------------------------

    @Test
    void criaMaquinasECriaAsListasQueFaltam() throws SQLException {
        ResultadoImportacao r = imp.importarMaquinas(folha(CAB_MAQ,
                l("T01", "Torno CNC", "SN1", "Tornos", "Móvel A · Prateleira 1 · Secção C", "Maquinar, Lda",
                        LocalDate.of(2020, 5, 1), "Ativo"),
                l("F01", "Fresadora", null, "Fresadoras", null, null, null, null)), false);

        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(2, r.criados());
        assertEquals(0, r.atualizados());
        assertEquals(Map.of("categorias", List.of("Tornos", "Fresadoras"), "localizações", List.of("Móvel A · Prateleira 1 · Secção C"),
                "fornecedores", List.of("Maquinar, Lda")), r.listasCriadas());

        Maquina t01 = maquina("T01");
        assertEquals("SN1", t01.nSerie());
        assertEquals(LocalDate.of(2020, 5, 1), t01.dataAquisicao());
        assertEquals(EstadoMaquina.ATIVO, t01.estado());
        Catalogos cat = inv.catalogos();
        assertEquals("Tornos", cat.categoria(t01.categoriaId()));
        assertEquals("Móvel A · Prateleira 1 · Secção C", cat.localizacao(t01.localizacaoId()));
        assertEquals("Maquinar, Lda", cat.fornecedor(t01.fornecedorId()));
        assertNull(maquina("F01").localizacaoId());
        // as categorias de máquinas são do tipo MAQUINA
        assertEquals(2, contar("SELECT COUNT(*) FROM categoria WHERE tipo = 'MAQUINA'"));
    }

    @Test
    void maquinaNovaComEstadoNasceAtivaEMudaComHistoricoCoerente() throws SQLException {
        imp.importarMaquinas(folha(CAB_MAQ,
                l("A1", "Ativa", null, "Tornos", null, null, null, "Ativo"),
                l("I1", "Parada", null, "Tornos", null, null, null, "inoperativo"),
                l("M1", "Em revisão", null, "Tornos", null, null, null, "MANUTENCAO"),
                l("B1", "Abatida", null, "Tornos", null, null, null, "Abatido")), false);

        assertEquals(EstadoMaquina.ATIVO, maquina("A1").estado());
        assertEquals(EstadoMaquina.INOPERATIVO, maquina("I1").estado());
        assertEquals(EstadoMaquina.MANUTENCAO, maquina("M1").estado());
        assertEquals(EstadoMaquina.ABATIDO, maquina("B1").estado());
        List<HistoricoEstado> h = estados.historico(maquina("B1").id()); // mais recente primeiro
        assertEquals(List.of(EstadoMaquina.ABATIDO, EstadoMaquina.ATIVO), h.stream().map(HistoricoEstado::estado).toList());
        assertEquals("Importação do Excel", h.get(0).motivo());
        assertEquals(1, estados.historico(maquina("A1").id()).size(), "ativa: só o registo inicial");
    }

    @Test
    void simulacaoNaoGravaNadaMasDizOMesmoQueAGravacao() throws SQLException {
        FolhaImportada f = folha(CAB_MAQ,
                l("T01", "Torno", null, "Tornos", "Loc 1", "Forn 1", null, "Abatido"),
                l("T02", "Torno 2", null, "Tornos", "Loc 1", null, null, null));
        ResultadoImportacao simulado = imp.importarMaquinas(f, true);
        assertFalse(simulado.aplicada());
        assertEquals(2, simulado.criados());
        assertEquals(0, contar("SELECT COUNT(*) FROM maquina"));
        assertEquals(0, contar("SELECT COUNT(*) FROM categoria"));
        assertEquals(0, contar("SELECT COUNT(*) FROM localizacao"));
        assertEquals(0, contar("SELECT COUNT(*) FROM historico_estado"));
        assertTrue(db.connection().getAutoCommit());

        ResultadoImportacao real = imp.importarMaquinas(f, false);
        assertTrue(real.aplicada());
        assertEquals(simulado.criados(), real.criados());
        assertEquals(simulado.listasCriadas(), real.listasCriadas());
        assertEquals(2, contar("SELECT COUNT(*) FROM maquina"));
    }

    // ---- máquinas: atualizar -----------------------------------------------

    private void baseComUmaMaquina() throws SQLException {
        imp.importarMaquinas(folha(CAB_MAQ, l("T01", "Torno", "SN1", "Tornos", "Loc 1", "Forn 1", LocalDate.of(2020, 5, 1), null)), false);
    }

    @Test
    void reimportarOMesmoFicheiroNaoAlteraNadaNemAvisa() throws SQLException {
        baseComUmaMaquina();
        ResultadoImportacao r = imp.importarMaquinas(folha(CAB_MAQ,
                l("T01", "Torno", "SN1", "Tornos", "Loc 1", "Forn 1", LocalDate.of(2020, 5, 1), "Ativo")), false);
        assertEquals(0, r.criados());
        assertEquals(0, r.atualizados());
        assertEquals(1, r.semAlteracoes());
        assertTrue(r.avisos().isEmpty() && r.listasCriadas().isEmpty());
        assertFalse(r.temAlteracoes());
    }

    @Test
    void atualizaOsDadosDeInventarioDeUmaMaquinaExistente() throws SQLException {
        baseComUmaMaquina();
        ResultadoImportacao r = imp.importarMaquinas(folha(CAB_MAQ,
                l("t01", "Torno CNC novo", "SN9", "Tornos", "Loc 2", null, LocalDate.of(2021, 1, 1), null)), false);
        assertEquals(1, r.atualizados());
        Maquina m = maquina("T01"); // o código existente mantém-se (o ficheiro só diferia em maiúsculas)
        assertEquals("T01", m.codigo());
        assertEquals("Torno CNC novo", m.descricao());
        assertEquals("SN9", m.nSerie());
        assertEquals(LocalDate.of(2021, 1, 1), m.dataAquisicao());
        assertNull(m.fornecedorId(), "célula vazia numa coluna presente limpa o campo opcional");
        assertEquals("Loc 2", inv.catalogos().localizacao(m.localizacaoId()));
        assertEquals(Map.of("localizações", List.of("Loc 2")), r.listasCriadas());
        assertEquals(1, contar("SELECT COUNT(*) FROM maquina"));
    }

    @Test
    void colunaAusenteMantemOValorAtual() throws SQLException {
        baseComUmaMaquina();
        ResultadoImportacao r = imp.importarMaquinas(folha(List.of("Código", "Descrição", "Categoria"),
                l("T01", "Só a descrição mudou", "Tornos")), false);
        assertEquals(1, r.atualizados());
        Maquina m = maquina("T01");
        assertEquals("Só a descrição mudou", m.descricao());
        assertEquals("SN1", m.nSerie());
        assertEquals(LocalDate.of(2020, 5, 1), m.dataAquisicao());
        assertEquals("Loc 1", inv.catalogos().localizacao(m.localizacaoId()));
        assertEquals("Forn 1", inv.catalogos().fornecedor(m.fornecedorId()));
    }

    @Test
    void oEstadoSoSeAplicaAMaquinasNovasEAvisaQuandoIgnora() throws SQLException {
        baseComUmaMaquina();
        ResultadoImportacao r = imp.importarMaquinas(folha(CAB_MAQ,
                l("T01", "Torno", "SN1", "Tornos", "Loc 1", "Forn 1", LocalDate.of(2020, 5, 1), "Abatido")), false);
        assertEquals(0, r.atualizados());
        assertEquals(EstadoMaquina.ATIVO, maquina("T01").estado(), "o estado de uma máquina existente não muda por aqui");
        assertEquals(1, estados.historico(maquina("T01").id()).size());
        assertEquals(1, r.avisos().size());
        assertTrue(r.avisos().get(0).contains("Alterar estado"), r.avisos().get(0));
    }

    @Test
    void casaListasPorNomeSemDistinguirMaiusculasNemAcentos() throws SQLException {
        inv.criarLocalizacao("Móvel A");
        inv.criarCategoria("Tornos CNC", TipoCategoria.MAQUINA);
        ResultadoImportacao r = imp.importarMaquinas(folha(CAB_MAQ,
                l("T01", "Torno", null, "  tornos   cnc ", "MOVEL a", null, null, null)), false);
        assertTrue(r.listasCriadas().isEmpty(), r.listasCriadas().toString());
        assertEquals(1, contar("SELECT COUNT(*) FROM localizacao"));
        assertEquals(1, contar("SELECT COUNT(*) FROM categoria"));
    }

    // ---- máquinas: erros e tudo-ou-nada ------------------------------------

    @Test
    void errosPorLinhaImpedemTudoMesmoAsLinhasBoasEAsListasCriadas() throws SQLException {
        ResultadoImportacao r = imp.importarMaquinas(folha(CAB_MAQ,
                l("T01", "Boa", null, "Tornos", "Loc nova", null, null, null),
                l(null, "Sem código", null, "Tornos", null, null, null, null),
                l("T03", null, null, "Tornos", null, null, null, null),
                l("T04", "Sem categoria", null, null, null, null, null, null),
                l("T05", "Estado mau", null, "Tornos", null, null, null, "Avariado"),
                l("T06", "Data má", null, "Tornos", null, null, "ontem", null),
                l("T07", "Futura", null, "Tornos", null, null, LocalDate.of(2026, 10, 6), null),
                l("t01", "Repetida", null, "Tornos", null, null, null, null),
                l("T09", "Data como número", null, "Tornos", null, null, 45000.0, null)), false);

        assertFalse(r.aplicada());
        assertEquals(List.of(
                "3: O código é obrigatório.",
                "4: A descrição é obrigatória.",
                "5: A categoria é obrigatória.",
                "6: O estado «Avariado» não é válido (use: Ativo, Inoperativo, Manutenção ou Abatido).",
                "7: A data de aquisição «ontem» não é uma data válida (use 31/12/2026 ou 2026-12-31).",
                "8: A data de aquisição não pode ser futura.",
                "9: O código t01 está repetido no ficheiro.",
                "10: A data de aquisição não é uma data válida (use uma célula de data ou 31/12/2026)."), mensagens(r));
        // tudo ou nada: nem a linha boa, nem as listas criadas, ficaram gravadas
        assertEquals(0, contar("SELECT COUNT(*) FROM maquina"));
        assertEquals(0, contar("SELECT COUNT(*) FROM categoria"));
        assertEquals(0, contar("SELECT COUNT(*) FROM localizacao"));
        assertTrue(db.connection().getAutoCommit());
    }

    @Test
    void problemasNosCabecalhos() throws SQLException {
        assertEquals(List.of("0: Falta a coluna obrigatória «Código».", "0: Falta a coluna obrigatória «Categoria»."),
                mensagens(imp.importarMaquinas(folha(List.of("Descrição", "Estado"), l("x", "Ativo")), false)));
        assertEquals(List.of("0: A coluna «Código» aparece mais do que uma vez no ficheiro."),
                mensagens(imp.importarMaquinas(folha(List.of("Código", "cod", "Descrição", "Categoria"),
                        l("a", "b", "c", "d")), false)));
        assertEquals(List.of("0: O ficheiro não tem linhas de dados (só o cabeçalho, ou está vazio)."),
                mensagens(imp.importarMaquinas(folha(CAB_MAQ), false)));
        assertEquals(3, mensagens(imp.importarMaquinas(new FolhaImportada(List.of(), List.of()), false)).size());
    }

    @Test
    void cabecalhosAlternativosEColunasDesconhecidasIgnoradas() throws SQLException {
        ResultadoImportacao r = imp.importarMaquinas(folha(
                List.of("COD", "Designação", "N.º de série", "CATEGORIA", "Notas internas", "", "Data de Aquisição"),
                l(12345.0, "Torno", 100345.0, "Tornos", "ignora-me", "x", "31/12/2019")), false);
        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(List.of("Notas internas"), r.colunasIgnoradas());
        Maquina m = maquina("12345"); // um código numérico no Excel não fica "12345.0"
        assertEquals("100345", m.nSerie());
        assertEquals(LocalDate.of(2019, 12, 31), m.dataAquisicao());
    }

    @Test
    void ignoraUmTituloEnotasPorCimaDosCabecalhos() throws SQLException {
        // linhas 1-2: título e nota; linha 3: cabeçalhos; a partir da 4: dados (os números são os do ficheiro)
        List<LinhaImportada> linhas = List.of(
                new LinhaImportada(2, Arrays.asList("Atualizado em 05/10/2026", null, null)),
                new LinhaImportada(3, Arrays.asList("Código", "Descrição", "Categoria")),
                new LinhaImportada(4, Arrays.asList("T01", "Torno", "Tornos")),
                new LinhaImportada(5, Arrays.asList(null, "Sem código", "Tornos")));
        ResultadoImportacao r = imp.importarMaquinas(new FolhaImportada(List.of("Inventário de máquinas — Oficina"), linhas), true);
        assertEquals(List.of("5: O código é obrigatório."), mensagens(r), "o erro aponta a linha certa do ficheiro");
        assertEquals(2, r.linhasLidas(), "só as linhas de dados contam");
        assertEquals(1, r.criados());
        // sem cabeçalho reconhecível em lado nenhum: os erros normais dizem o que falta
        ResultadoImportacao semNada = imp.importarMaquinas(new FolhaImportada(List.of("Título"),
                List.of(new LinhaImportada(2, Arrays.asList("a", "b")))), true);
        assertTrue(mensagens(semNada).get(0).contains("Falta a coluna obrigatória"), mensagens(semNada).toString());
    }

    @Test
    void formulaSemValorCalculadoEUmErroEmVezDeUmValorErrado() throws SQLException {
        ResultadoImportacao r = imp.importarMaquinas(folha(List.of("Código", "Descrição", "Categoria", "Notas"),
                l("T01", FolhaImportada.FORMULA_SEM_VALOR, "Tornos", "ignorada"),
                l("T02", "Boa", "Tornos", FolhaImportada.FORMULA_SEM_VALOR)), false);
        assertEquals(List.of("2: A coluna «Descrição» tem uma fórmula sem valor calculado (o ficheiro nunca foi aberto no Excel). "
                + "Abra-o no Excel, guarde-o e importe de novo."), mensagens(r), "só falha se a célula for de uma coluna lida");
        assertEquals(0, contar("SELECT COUNT(*) FROM maquina"));
    }

    // ---- artigos -----------------------------------------------------------

    @Test
    void criaArtigosComStockInicialComoEntrada() throws SQLException {
        ResultadoImportacao r = imp.importarArtigos(folha(CAB_ART,
                l("P01", "Pastilha", "Pastilhas", "Móvel A", "Sandvik", "cx", 5.0, 12.0),
                l("O01", "Óleo", "Lubrificantes", null, null, "L", "2,5", null),
                l("X01", "Sem unidade nem stock", "Pastilhas", null, null, null, null, 0.0)), false);

        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(3, r.criados());
        assertEquals(12, stock.stockDe(artigo("P01").id()), 1e-9);
        assertEquals(0, stock.stockDe(artigo("O01").id()), 1e-9);
        var mov = stock.movimentos(artigo("P01").id()).get(0);
        assertEquals(TipoMovimento.ENTRADA, mov.tipo());
        assertEquals(HOJE, mov.dataMov());
        assertEquals("Importação do Excel", mov.nota());
        assertEquals("l", artigo("O01").unidade(), "unidade sem distinguir maiúsculas");
        assertEquals(2.5, artigo("O01").stockMinimo(), 1e-9);
        assertEquals("un", artigo("X01").unidade(), "por defeito, «un»");
        assertEquals(0, stock.movimentos(artigo("X01").id()).size(), "stock 0 não cria movimento");
        // as categorias de artigos criadas são do tipo ARTIGO (não de máquinas)
        assertEquals(2, contar("SELECT COUNT(*) FROM categoria WHERE tipo = 'ARTIGO'"));
    }

    @Test
    void usaCategoriasDeArtigosExistentesDeQualquerTipoDeArtigo() throws SQLException {
        inv.criarCategoria("Brocas", TipoCategoria.FERRAMENTA);
        inv.criarCategoria("Tornos", TipoCategoria.MAQUINA); // é de máquinas: não serve para artigos
        ResultadoImportacao r = imp.importarArtigos(folha(CAB_ART,
                l("B1", "Broca", "brocas", null, null, null, null, null),
                l("B2", "Outra", "Tornos", null, null, null, null, null)), false);
        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(Map.of("categorias", List.of("Tornos")), r.listasCriadas(), "«Tornos» de artigos é uma categoria nova");
        assertEquals(3, contar("SELECT COUNT(*) FROM categoria"));
        assertEquals(1, contar("SELECT COUNT(*) FROM categoria WHERE nome = 'Brocas' AND tipo = 'FERRAMENTA'"));
    }

    @Test
    void artigosExistentesAtualizamDadosMasNaoOStock() throws SQLException {
        imp.importarArtigos(folha(CAB_ART, l("P01", "Pastilha", "Pastilhas", null, null, "cx", 5.0, 12.0)), false);

        // mesmo ficheiro, stock igual: nada a fazer e sem aviso
        ResultadoImportacao igual = imp.importarArtigos(folha(CAB_ART,
                l("P01", "Pastilha", "Pastilhas", null, null, "cx", 5.0, 12.0)), false);
        assertEquals(1, igual.semAlteracoes());
        assertTrue(igual.avisos().isEmpty());

        ResultadoImportacao r = imp.importarArtigos(folha(CAB_ART,
                l("p01", "Pastilha CNMG", "Pastilhas", "Loc", null, "un", 8.0, 999.0)), false);
        assertEquals(1, r.atualizados());
        Artigo a = artigo("P01");
        assertEquals("Pastilha CNMG", a.descricao());
        assertEquals("un", a.unidade());
        assertEquals(8, a.stockMinimo(), 1e-9);
        assertEquals(12, stock.stockDe(a.id()), 1e-9, "o stock não muda por importação");
        assertEquals(1, stock.movimentos(a.id()).size());
        assertEquals(1, r.avisos().size());
        assertTrue(r.avisos().get(0).contains("movimentos de stock"), r.avisos().get(0));
    }

    @Test
    void colunasAusentesEmArtigosMantemOValorAtual() throws SQLException {
        imp.importarArtigos(folha(CAB_ART, l("P01", "Pastilha", "Pastilhas", "Loc", "Forn", "cx", 5.0, null)), false);
        imp.importarArtigos(folha(List.of("Código", "Descrição", "Categoria"), l("P01", "Nova descrição", "Pastilhas")), false);
        Artigo a = artigo("P01");
        assertEquals("Nova descrição", a.descricao());
        assertEquals("cx", a.unidade());
        assertEquals(5, a.stockMinimo(), 1e-9);
        assertEquals("Loc", inv.catalogos().localizacao(a.localizacaoId()));
        // coluna «Stock mínimo» presente mas vazia: passa a 0
        imp.importarArtigos(folha(List.of("Código", "Descrição", "Categoria", "Stock mínimo"),
                l("P01", "Nova descrição", "Pastilhas", null)), false);
        assertEquals(0, artigo("P01").stockMinimo(), 1e-9);
    }

    @Test
    void errosDeArtigosSaoPorLinhaETudoOuNada() throws SQLException {
        ResultadoImportacao r = imp.importarArtigos(folha(CAB_ART,
                l("A1", "Boa", "Cat", null, null, null, null, 10.0),
                l("A2", "Unidade má", "Cat", null, null, "caixote", null, null),
                l("A3", "Stock negativo", "Cat", null, null, null, null, -1.0),
                l("A4", "Mínimo com ponto", "Cat", null, null, null, "1.000", null),
                l("A5", "Mínimo enorme", "Cat", null, null, null, 1e12, null),
                l("A6", "Texto no stock", "Cat", null, null, null, null, "muito")), false);
        assertEquals(List.of(
                "3: A unidade «caixote» não é válida (use: un, par, cx, rolo, m, kg, g, l).",
                "4: O stock não pode ser negativo.",
                "5: O stock mínimo: use a vírgula como separador decimal (ex.: 2,5).",
                "6: O stock mínimo é demasiado grande (máximo 1 000 000 000).",
                "7: O stock não é um número válido."), mensagens(r));
        assertEquals(0, contar("SELECT COUNT(*) FROM artigo"));
        assertEquals(0, contar("SELECT COUNT(*) FROM movimento_stock"));
        assertEquals(0, contar("SELECT COUNT(*) FROM categoria"));
    }

    @Test
    void faltaDeStockNaoImpedeUmArtigoNovoSemStock() throws SQLException {
        // 100 artigos novos, com stock, num só pedido: é uma transação e cada entrada fica registada
        List<Object[]> linhas = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            linhas.add(l("A" + i, "Artigo " + i, "Cat", null, null, "un", 1.0, (double) i));
        }
        ResultadoImportacao r = imp.importarArtigos(folha(CAB_ART, linhas.toArray(Object[][]::new)), false);
        assertEquals(100, r.criados());
        assertEquals(100, contar("SELECT COUNT(*) FROM movimento_stock"));
        assertEquals(5050, contar("SELECT CAST(SUM(quantidade) AS INTEGER) FROM movimento_stock"));
    }

    @Test
    void datasQueNaoExistemSaoRecusadasEmVezDeCorrigidas() throws SQLException {
        // sem leitura estrita, 30/02 passava a 28/02 e 31/04 a 30/04 em silêncio
        ResultadoImportacao r = imp.importarMaquinas(folha(CAB_MAQ,
                l("T01", "Fevereiro", null, "Tornos", null, null, "30/02/2026", null),
                l("T02", "Abril", null, "Tornos", null, null, "31-04-2026", null),
                l("T03", "Não bissexto", null, "Tornos", null, null, "29.02.2025", null),
                l("T04", "Bissexto", null, "Tornos", null, null, "29/02/2024", null)), false);
        assertEquals(List.of(
                "2: A data de aquisição «30/02/2026» não é uma data válida (use 31/12/2026 ou 2026-12-31).",
                "3: A data de aquisição «31-04-2026» não é uma data válida (use 31/12/2026 ou 2026-12-31).",
                "4: A data de aquisição «29.02.2025» não é uma data válida (use 31/12/2026 ou 2026-12-31)."), mensagens(r));
        assertEquals(0, contar("SELECT COUNT(*) FROM maquina"));

        r = imp.importarMaquinas(folha(CAB_MAQ, l("T04", "Bissexto", null, "Tornos", null, null, "29/02/2024", null)), false);
        assertTrue(r.aplicada(), mensagens(r).toString());
        assertEquals(LocalDate.of(2024, 2, 29), maquina("T04").dataAquisicao());
    }
}
