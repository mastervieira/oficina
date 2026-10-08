package pt.oficina.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import pt.oficina.dao.CategoriaDao;
import pt.oficina.dao.HistoricoEstadoDao;
import pt.oficina.dao.IntervencaoDao;
import pt.oficina.dao.IntervencaoDao.MaquinaPlano;
import pt.oficina.dao.MaquinaDao;
import pt.oficina.dao.PlanoManutencaoDao;
import pt.oficina.db.Tx;
import pt.oficina.model.ConsumoMaterial;
import pt.oficina.model.Intervencao;
import pt.oficina.model.Categoria;
import pt.oficina.model.HistoricoEstado;
import pt.oficina.model.Maquina;
import pt.oficina.model.PlanoDaMaquina;
import pt.oficina.model.PlanoComPrazo;
import pt.oficina.model.PlanoManutencao;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.model.enums.TipoCategoria;
import pt.oficina.model.enums.TipoMovimento;

/**
 * Módulo 3: planos de manutenção por TIPO de máquina (periodicidade em meses de calendário) e intervenções.
 * Cada máquina tem os planos do seu tipo; a próxima data é por máquina: última intervenção dessa máquina ligada ao plano
 * + periodicidade; sem intervenção, vencido.
 * O consumo de material regista-se apenas como SAIDA em movimento_stock, com nota.
 */
public final class ManutencaoService {

    /** Janela do itinerário: vencidas ou a vencer nos próximos 30 dias. */
    public static final int JANELA_DIAS = 30;
    public static final int MAX_PERIODICIDADE_MESES = 600;

    private final Connection c;
    private final Clock relogio;
    private final StockService stock;
    private final MaquinaDao maquinas;
    private final PlanoManutencaoDao planos;
    private final CategoriaDao categorias;
    private final IntervencaoDao intervencoes;
    private final HistoricoEstadoDao historico;

    public ManutencaoService(Connection c, StockService stock) {
        this(c, stock, Clock.systemDefaultZone());
    }

    public ManutencaoService(Connection c, StockService stock, Clock relogio) {
        this.c = c;
        this.relogio = relogio;
        this.stock = stock;
        this.maquinas = new MaquinaDao(c);
        this.planos = new PlanoManutencaoDao(c);
        this.categorias = new CategoriaDao(c);
        this.intervencoes = new IntervencaoDao(c);
        this.historico = new HistoricoEstadoDao(c);
    }

    // ---- Consultas -------------------------------------------------------

    /** Máquinas onde faz sentido planear ou registar manutenção (todas menos as ABATIDO). */
    public List<Maquina> maquinasParaManutencao() throws SQLException {
        return maquinas.listar().stream().filter(m -> m.estado() != EstadoMaquina.ABATIDO).toList();
    }

    /** Todos os planos (de todos os tipos de máquina), por tipo e tarefa. */
    public List<PlanoManutencao> planosDeManutencao() throws SQLException {
        return planos.listar();
    }

    /** Os planos de um tipo de máquina. */
    public List<PlanoManutencao> planosDoTipo(long categoriaId) throws SQLException {
        return planos.listarPorCategoria(categoriaId);
    }

    /**
     * Os planos de cada máquina (as abatidas não contam): os do seu tipo, com a última e a próxima intervenção
     * dessa máquina. Por máquina e, dentro dela, pela ordem dos planos.
     */
    public List<PlanoDaMaquina> planosDasMaquinas() throws SQLException {
        Map<MaquinaPlano, LocalDate> ultimas = intervencoes.ultimaPorMaquinaEPlano();
        Map<Long, List<PlanoManutencao>> porTipo = new HashMap<>();
        for (PlanoManutencao p : planos.listar()) {
            porTipo.computeIfAbsent(p.categoriaId(), k -> new ArrayList<>()).add(p);
        }
        List<PlanoDaMaquina> r = new ArrayList<>();
        for (Maquina m : maquinas.listar()) {
            if (m.estado() == EstadoMaquina.ABATIDO) {
                continue;
            }
            for (PlanoManutencao p : porTipo.getOrDefault(m.categoriaId(), List.of())) {
                r.add(new PlanoDaMaquina(m, PlanoComPrazo.de(p, ultimas.get(new MaquinaPlano(m.id(), p.id())))));
            }
        }
        return r;
    }

    public List<Intervencao> intervencoes() throws SQLException {
        return intervencoes.listar();
    }

    /**
     * Planos vencidos ou que vencem nos próximos {@link #JANELA_DIAS} dias, só de máquinas ATIVO.
     * Primeiro os sem intervenção, depois por próxima data crescente.
     */
    public List<PlanoDaMaquina> itinerario(LocalDate hoje) throws SQLException {
        LocalDate limite = hoje.plusDays(JANELA_DIAS);
        return planosDasMaquinas().stream()
                .filter(p -> p.maquina().estado() == EstadoMaquina.ATIVO)
                .filter(p -> p.prazo().proxima() == null || !p.prazo().proxima().isAfter(limite))
                .sorted(Comparator
                        .comparing((PlanoDaMaquina i) -> i.prazo().proxima(), Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(i -> i.maquina().codigo(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(i -> i.prazo().plano().tarefa(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ---- Planos ----------------------------------------------------------

    /**
     * Cria (id null) ou altera um plano de um tipo de máquina (uma categoria de máquinas). Na edição só a tarefa e a
     * periodicidade mudam. Num tipo, a tarefa não se repete (sem distinguir maiúsculas nem acentos).
     */
    public PlanoManutencao guardarPlano(PlanoManutencao p) throws SQLException {
        String tarefa = p.tarefa() == null ? "" : p.tarefa().trim();
        if (tarefa.isEmpty()) {
            throw ValidacaoException.obrigatorio("A tarefa");
        }
        if (p.periodicidadeMeses() < 1 || p.periodicidadeMeses() > MAX_PERIODICIDADE_MESES) {
            throw new ValidacaoException("A periodicidade tem de estar entre 1 e " + MAX_PERIODICIDADE_MESES + " meses.");
        }
        return Tx.call(c, () -> {
            PlanoManutencao atual = p.id() == null ? null : planos.porId(p.id())
                    .orElseThrow(() -> new ValidacaoException("O plano já não existe."));
            long categoriaId = atual == null ? p.categoriaId() : atual.categoriaId();
            Categoria tipo = categorias.porId(categoriaId).filter(cat -> cat.tipo() == TipoCategoria.MAQUINA)
                    .orElseThrow(() -> new ValidacaoException("O tipo de máquina não existe."));
            for (PlanoManutencao outro : planos.listarPorCategoria(categoriaId)) {
                if (!outro.id().equals(p.id()) && Texto.chave(outro.tarefa()).equals(Texto.chave(tarefa))) {
                    throw new ValidacaoException("O tipo «" + tipo.nome() + "» já tem um plano com a tarefa «"
                            + outro.tarefa() + "».");
                }
            }
            if (atual == null) {
                return planos.inserir(new PlanoManutencao(null, categoriaId, tarefa, p.periodicidadeMeses()));
            }
            PlanoManutencao novo = new PlanoManutencao(atual.id(), categoriaId, tarefa, p.periodicidadeMeses());
            planos.atualizar(novo);
            return novo;
        });
    }

    /** Só se pode remover um plano que ainda não tenha intervenções ligadas (o histórico mantém-se íntegro). */
    public void apagarPlano(long id) throws SQLException {
        Tx.run(c, () -> {
            if (intervencoes.existeParaPlano(id)) {
                throw new ValidacaoException("Este plano já tem intervenções registadas e não pode ser removido.");
            }
            planos.apagar(id);
        });
    }

    // ---- Intervenções ----------------------------------------------------

    /**
     * Regista uma intervenção e, na mesma transação, o material consumido como SAIDA em movimento_stock
     * (com nota a referir a intervenção). Se faltar stock em qualquer artigo, nada é gravado.
     * Só uma intervenção PREVENTIVA ou PROGRAMADA pode ficar ligada a um plano. Numa máquina abatida só se aceitam
     * intervenções até à data do abate (o histórico anterior pode importar-se; depois do abate não há manutenção).
     */
    public Intervencao registarIntervencao(Intervencao i, List<ConsumoMaterial> consumos) throws SQLException {
        if (i.tipo() == null) {
            throw new ValidacaoException("O tipo de intervenção é obrigatório.");
        }
        if (i.dataInterv() == null) {
            throw new ValidacaoException("A data é obrigatória.");
        }
        Datas.naoFutura(i.dataInterv(), LocalDate.now(relogio), "A data");
        if (i.custo() != null) {
            Limites.validar(i.custo(), "O custo");
        }
        if (i.planoId() != null && !i.tipo().cumprePlano()) {
            throw new ValidacaoException("Só uma intervenção preventiva ou programada pode ser ligada a um plano.");
        }
        String descricao = i.descricao() == null || i.descricao().isBlank() ? null : i.descricao().trim();
        List<ConsumoMaterial> material = consumos == null ? List.of() : consumos;

        return Tx.call(c, () -> {
            Maquina maquina = maquinas.porId(i.maquinaId())
                    .orElseThrow(() -> new ValidacaoException("A máquina não existe."));
            if (maquina.estado() == EstadoMaquina.ABATIDO) {
                LocalDate abate = historico.listarPorMaquina(maquina.id()).stream()
                        .filter(h -> h.estado() == EstadoMaquina.ABATIDO)
                        .map(HistoricoEstado::dataEstado)
                        .findFirst() // o mais recente
                        .orElse(null);
                if (abate == null || i.dataInterv().isAfter(abate)) {
                    throw new ValidacaoException("A máquina " + maquina.codigo() + " está abatida"
                            + (abate == null ? "" : " desde " + Datas.formatar(abate))
                            + ": não se registam intervenções depois do abate.");
                }
            }
            if (i.planoId() != null) {
                PlanoManutencao plano = planos.porId(i.planoId())
                        .orElseThrow(() -> new ValidacaoException("O plano não existe."));
                if (plano.categoriaId() != maquina.categoriaId()) {
                    throw new ValidacaoException("O plano não se aplica ao tipo desta máquina.");
                }
            }
            Intervencao gravada = intervencoes.inserir(new Intervencao(null, i.maquinaId(), i.planoId(),
                    i.dataInterv(), i.tipo(), descricao, i.custo()));
            String nota = "Intervenção #" + gravada.id() + " — " + maquina.codigo() + " (" + i.tipo() + ")";
            for (ConsumoMaterial cm : material) {
                stock.registar(cm.artigoId(), TipoMovimento.SAIDA, i.dataInterv(), cm.quantidade(), nota);
            }
            return gravada;
        });
    }
}
