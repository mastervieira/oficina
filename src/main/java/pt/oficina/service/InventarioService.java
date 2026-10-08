package pt.oficina.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.text.Collator;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import pt.oficina.dao.ArtigoDao;
import pt.oficina.dao.CategoriaDao;
import pt.oficina.dao.FornecedorDao;
import pt.oficina.dao.HistoricoEstadoDao;
import pt.oficina.dao.LocalizacaoDao;
import pt.oficina.dao.MaquinaDao;
import pt.oficina.db.Tx;
import pt.oficina.model.Artigo;
import pt.oficina.model.Categoria;
import pt.oficina.model.Fornecedor;
import pt.oficina.model.HistoricoEstado;
import pt.oficina.model.Localizacao;
import pt.oficina.model.Maquina;
import pt.oficina.model.Unidades;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.model.enums.TipoCategoria;

/** Módulo 1: máquinas, artigos e listas de apoio. Só se eliminam máquinas e artigos que nunca foram usados. */
public final class InventarioService {

    /** Limites das imagens (ver {@link Imagens}); mantêm-se aqui por compatibilidade. */
    public static final int MAX_IMAGEM_BYTES = Imagens.MAX_BYTES;
    public static final int MAX_IMAGEM_LADO = Imagens.MAX_LADO;
    public static final long MAX_IMAGEM_PIXEIS = Imagens.MAX_PIXEIS;

    private final Connection c;
    private final Clock relogio;
    private final CategoriaDao categorias;
    private final LocalizacaoDao localizacoes;
    private final FornecedorDao fornecedores;
    private final MaquinaDao maquinas;
    private final ArtigoDao artigos;
    private final HistoricoEstadoDao historico;

    public InventarioService(Connection c) {
        this(c, Clock.systemDefaultZone());
    }

    public InventarioService(Connection c, Clock relogio) {
        this.c = c;
        this.relogio = relogio;
        this.categorias = new CategoriaDao(c);
        this.localizacoes = new LocalizacaoDao(c);
        this.fornecedores = new FornecedorDao(c);
        this.maquinas = new MaquinaDao(c);
        this.artigos = new ArtigoDao(c);
        this.historico = new HistoricoEstadoDao(c);
    }

    // ---- Consultas -------------------------------------------------------

    /**
     * Listas de apoio ordenadas por nome segundo as regras do português (o COLLATE NOCASE do SQLite só trata
     * ASCII e poria "Óleos" depois de "Vários").
     */
    public Catalogos catalogos() throws SQLException {
        return new Catalogos(
                ordenar(categorias.listar(false), Categoria::nome),
                ordenar(categorias.listar(true), Categoria::nome),
                ordenar(localizacoes.listar(), Localizacao::nome),
                ordenar(fornecedores.listar(), Fornecedor::nome));
    }

    private static <T> List<T> ordenar(List<T> itens, Function<T, String> nome) {
        Collator pt = Collator.getInstance(Locale.of("pt", "PT"));
        return itens.stream().sorted(Comparator.comparing(nome, pt)).toList();
    }

    public List<Maquina> maquinas() throws SQLException {
        return maquinas.listar();
    }

    public List<Artigo> artigos() throws SQLException {
        return artigos.listar();
    }

    // ---- Máquinas --------------------------------------------------------

    /** Grava a máquina sem mexer na imagem que já tenha. */
    public Maquina guardarMaquina(Maquina m) throws SQLException {
        return guardarMaquina(m, false, null);
    }

    /** A imagem (bytes do ficheiro) da máquina, ou null se não tem. Lê-se à parte porque pode ser grande. */
    public byte[] imagemMaquina(long id) throws SQLException {
        return maquinas.imagem(id);
    }

    /**
     * Cria (id null) ou atualiza uma máquina. Na criação o estado é sempre ATIVO e grava-se a primeira
     * linha de historico_estado na mesma transação. Na edição o estado não é tocado.
     * Se {@code alterarImagem}, grava também a imagem (null retira-a); senão, a que a máquina já tenha fica como está.
     */
    public Maquina guardarMaquina(Maquina m, boolean alterarImagem, byte[] imagem) throws SQLException {
        if (alterarImagem && imagem != null) {
            validarImagem(imagem);
        }
        String codigo = obrigatorio(m.codigo(), "O código");
        String descricao = obrigatorio(m.descricao(), "A descrição");
        validarCategoria(m.categoriaId(), false);
        LocalDate hoje = LocalDate.now(relogio);
        if (m.dataAquisicao() != null) {
            Datas.naoFutura(m.dataAquisicao(), hoje, "A data de aquisição");
        }
        if (maquinas.existeCodigo(codigo, m.id())) {
            throw new ValidacaoException("Já existe uma máquina com o código " + codigo + ".");
        }
        Maquina limpa = new Maquina(m.id(), codigo, descricao, opcional(m.nSerie()), m.categoriaId(),
                m.localizacaoId(), m.fornecedorId(), m.dataAquisicao(), EstadoMaquina.ATIVO);
        return Tx.call(c, () -> {
            Maquina gravada;
            if (m.id() == null) {
                gravada = maquinas.inserir(limpa);
                historico.inserir(new HistoricoEstado(null, gravada.id(), hoje,
                        EstadoMaquina.ATIVO, "Registo inicial"));
            } else {
                maquinas.atualizar(limpa);
                gravada = maquinas.porId(m.id()).orElseThrow(() -> new ValidacaoException("A máquina já não existe."));
            }
            if (alterarImagem) {
                maquinas.definirImagem(gravada.id(), imagem);
            }
            return gravada;
        });
    }

    // ---- Artigos ---------------------------------------------------------

    /** Grava o artigo sem mexer na imagem que já tenha. */
    public Artigo guardarArtigo(Artigo a) throws SQLException {
        return guardarArtigo(a, false, null);
    }

    /** A imagem (bytes do ficheiro) do artigo, ou null se não tem. Lê-se à parte porque pode ser grande. */
    public byte[] imagemArtigo(long id) throws SQLException {
        return artigos.imagem(id);
    }

    /**
     * Grava o artigo e, se {@code alterarImagem}, também a imagem (null retira-a), tudo numa só transação.
     * Se {@code alterarImagem} é false, a imagem que o artigo já tenha fica como está.
     */
    public Artigo guardarArtigo(Artigo a, boolean alterarImagem, byte[] imagem) throws SQLException {
        if (alterarImagem && imagem != null) {
            validarImagem(imagem);
        }
        String codigo = obrigatorio(a.codigo(), "O código");
        String descricao = obrigatorio(a.descricao(), "A descrição");
        validarCategoria(a.categoriaId(), true);
        if (a.unidade() == null || !Unidades.LISTA.contains(a.unidade())) {
            throw new ValidacaoException("A unidade é inválida.");
        }
        Limites.validar(a.stockMinimo(), "O stock mínimo");
        if (artigos.existeCodigo(codigo, a.id())) {
            throw new ValidacaoException("Já existe um artigo com o código " + codigo + ".");
        }
        Artigo limpo = new Artigo(a.id(), codigo, descricao, a.categoriaId(), a.localizacaoId(),
                a.fornecedorId(), a.unidade(), a.stockMinimo());
        return Tx.call(c, () -> {
            Artigo gravado;
            if (a.id() == null) {
                gravado = artigos.inserir(limpo);
            } else {
                artigos.atualizar(limpo);
                gravado = artigos.porId(a.id()).orElseThrow(() -> new ValidacaoException("O artigo já não existe."));
            }
            if (alterarImagem) {
                artigos.definirImagem(gravado.id(), imagem);
            }
            return gravado;
        });
    }

    /** Recusa o que não é uma imagem PNG, JPEG, GIF ou BMP aceitável. Só lê o cabeçalho. Ver {@link Imagens#validar}. */
    public static void validarImagem(byte[] imagem) {
        Imagens.validar(imagem);
    }

    // ---- Eliminar --------------------------------------------------------

    /** Só se elimina um artigo que nunca teve movimentos de stock (a chave estrangeira recusa os restantes). */
    public void apagarArtigo(long id) throws SQLException {
        try {
            Tx.run(c, () -> artigos.apagar(id));
        } catch (SQLException e) {
            traduzirRestricao(e, "O artigo tem movimentos de stock e não pode ser eliminado.");
        }
    }

    /**
     * Só se elimina uma máquina que nunca teve intervenções nem mudanças de estado. Se tiver, deve
     * abater-se (estado ABATIDO). Apagar o registo inicial do histórico e a máquina é uma só transação.
     */
    public void apagarMaquina(long id) throws SQLException {
        try {
            Tx.run(c, () -> {
                if (historico.listarPorMaquina(id).size() > 1) {
                    throw new ValidacaoException("A máquina tem mudanças de estado registadas e não pode ser "
                            + "eliminada. Altere o estado para Abatido.");
                }
                historico.apagarDaMaquina(id); // só existe a linha "Registo inicial"
                maquinas.apagar(id);
            });
        } catch (SQLException e) {
            traduzirRestricao(e, "A máquina tem intervenções registadas e não pode ser eliminada. "
                    + "Altere o estado para Abatido.");
        }
    }

    /** Uma falha de chave estrangeira significa "tem registos associados": explica-se em vez de mostrar o erro SQL. */
    private static void traduzirRestricao(SQLException e, String mensagem) throws SQLException {
        if (e.getMessage() != null && e.getMessage().contains("FOREIGN KEY")) {
            throw new ValidacaoException(mensagem);
        }
        throw e;
    }

    // ---- Listas de apoio -------------------------------------------------

    public Categoria criarCategoria(String nome, TipoCategoria tipo) throws SQLException {
        String n = obrigatorio(nome, "O nome");
        if (categorias.existeNome(n, tipo.eDeArtigos(), null)) {
            throw new ValidacaoException("Já existe uma categoria com o nome " + n + ".");
        }
        return categorias.inserir(new Categoria(null, n, tipo));
    }

    public void renomearCategoria(long id, String nome) throws SQLException {
        String n = obrigatorio(nome, "O nome");
        Categoria atual = categorias.porId(id).orElseThrow(() -> new ValidacaoException("A categoria não existe."));
        if (categorias.existeNome(n, atual.tipo().eDeArtigos(), id)) {
            throw new ValidacaoException("Já existe uma categoria com o nome " + n + ".");
        }
        categorias.atualizarNome(id, n);
    }

    public Localizacao criarLocalizacao(String nome) throws SQLException {
        String n = obrigatorio(nome, "O nome");
        if (localizacoes.existeNome(n, null)) {
            throw new ValidacaoException("Já existe uma localização com o nome " + n + ".");
        }
        return localizacoes.inserir(new Localizacao(null, n));
    }

    public void renomearLocalizacao(long id, String nome) throws SQLException {
        String n = obrigatorio(nome, "O nome");
        if (localizacoes.existeNome(n, id)) {
            throw new ValidacaoException("Já existe uma localização com o nome " + n + ".");
        }
        if (!localizacoes.atualizarNome(id, n)) {
            throw new ValidacaoException("A localização já não existe.");
        }
    }

    public Fornecedor criarFornecedor(String nome, String contacto) throws SQLException {
        String n = obrigatorio(nome, "O nome");
        if (fornecedores.existeNome(n, null)) {
            throw new ValidacaoException("Já existe um fornecedor com o nome " + n + ".");
        }
        return fornecedores.inserir(new Fornecedor(null, n, opcional(contacto)));
    }

    public void atualizarFornecedor(long id, String nome, String contacto) throws SQLException {
        String n = obrigatorio(nome, "O nome");
        if (fornecedores.existeNome(n, id)) {
            throw new ValidacaoException("Já existe um fornecedor com o nome " + n + ".");
        }
        if (!fornecedores.atualizar(new Fornecedor(id, n, opcional(contacto)))) {
            throw new ValidacaoException("O fornecedor já não existe.");
        }
    }

    // ---- Validação -------------------------------------------------------

    /** Máquinas usam categorias do tipo MAQUINA; artigos usam as dos outros tipos (ARTIGO, FERRAMENTA, ...). */
    private void validarCategoria(long id, boolean deArtigos) throws SQLException {
        boolean ok = categorias.porId(id).filter(cat -> cat.tipo().eDeArtigos() == deArtigos).isPresent();
        if (!ok) {
            throw new ValidacaoException("A categoria escolhida não é válida para este registo.");
        }
    }

    private static String obrigatorio(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw ValidacaoException.obrigatorio(campo);
        }
        return valor.trim();
    }

    private static String opcional(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }
}
