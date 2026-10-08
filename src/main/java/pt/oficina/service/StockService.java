package pt.oficina.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import pt.oficina.dao.ArtigoDao;
import pt.oficina.dao.MovimentoStockDao;
import pt.oficina.db.Tx;
import pt.oficina.model.Artigo;
import pt.oficina.model.MovimentoStock;
import pt.oficina.model.StockArtigo;
import pt.oficina.model.enums.TipoMovimento;

/**
 * Módulo 2: movimentos de ENTRADA/SAIDA. O stock atual nunca é guardado: calcula-se pela soma dos movimentos.
 * Os movimentos não se editam nem se apagam; uma correção faz-se com um movimento de sentido contrário.
 */
public final class StockService {

    private static final double EPS = 1e-9;

    private final Connection c;
    private final Clock relogio;
    private final ArtigoDao artigos;
    private final MovimentoStockDao movimentos;

    public StockService(Connection c) {
        this(c, Clock.systemDefaultZone());
    }

    public StockService(Connection c, Clock relogio) {
        this.c = c;
        this.relogio = relogio;
        this.artigos = new ArtigoDao(c);
        this.movimentos = new MovimentoStockDao(c);
    }

    public List<StockArtigo> stockAtual() throws SQLException {
        Map<Long, Double> stocks = movimentos.stocks();
        return artigos.listar().stream()
                .map(a -> new StockArtigo(a, stocks.getOrDefault(a.id(), 0.0)))
                .toList();
    }

    /** Artigos com stock abaixo do mínimo. */
    public List<StockArtigo> alertas() throws SQLException {
        return stockAtual().stream().filter(StockArtigo::abaixoDoMinimo).toList();
    }

    public double stockDe(long artigoId) throws SQLException {
        return movimentos.stockAtual(artigoId);
    }

    /** Movimentos de um artigo, ou de todos se {@code artigoId} for null (mais recentes primeiro). */
    public List<MovimentoStock> movimentos(Long artigoId) throws SQLException {
        return movimentos.listar(artigoId);
    }

    /**
     * Regista um movimento. Uma SAIDA não pode exceder o stock disponível. Também é usado pelo módulo de
     * manutenção para registar o consumo de material numa intervenção (SAIDA com nota).
     */
    public MovimentoStock registar(long artigoId, TipoMovimento tipo, LocalDate data, double quantidade,
            String nota) throws SQLException {
        if (tipo == null) {
            throw new ValidacaoException("O tipo de movimento é obrigatório.");
        }
        if (data == null) {
            throw new ValidacaoException("A data é obrigatória.");
        }
        Datas.naoFutura(data, LocalDate.now(relogio), "A data");
        if (Double.isNaN(quantidade) || Double.isInfinite(quantidade) || quantidade <= 0) {
            throw new ValidacaoException("A quantidade tem de ser superior a zero.");
        }
        Limites.validar(quantidade, "A quantidade");
        String notaLimpa = nota == null || nota.isBlank() ? null : nota.trim();
        return Tx.call(c, () -> {
            Artigo artigo = artigos.porId(artigoId)
                    .orElseThrow(() -> new ValidacaoException("O artigo não existe."));
            if (tipo == TipoMovimento.SAIDA) {
                double disponivel = movimentos.stockAtual(artigoId);
                if (quantidade > disponivel + EPS) {
                    throw new ValidacaoException("Stock insuficiente de " + artigo.codigo()
                            + ": disponível " + Numeros.formatar(disponivel) + " " + artigo.unidade() + ".");
                }
            }
            return movimentos.inserir(new MovimentoStock(null, artigoId, data, tipo, quantidade, notaLimpa));
        });
    }
}
