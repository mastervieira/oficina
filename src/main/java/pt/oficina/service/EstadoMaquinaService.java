package pt.oficina.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import pt.oficina.dao.HistoricoEstadoDao;
import pt.oficina.dao.MaquinaDao;
import pt.oficina.db.Tx;
import pt.oficina.model.HistoricoEstado;
import pt.oficina.model.Maquina;
import pt.oficina.model.enums.EstadoMaquina;

/**
 * Módulo 4: estado das máquinas (ATIVO, INOPERATIVO, MANUTENCAO, ABATIDO). Cada mudança atualiza a máquina e
 * acrescenta uma linha a historico_estado na mesma transação. O histórico nunca se altera; só se apaga o registo
 * inicial de uma máquina que nunca mudou de estado e está a ser eliminada (ver InventarioService.apagarMaquina).
 */
public final class EstadoMaquinaService {

    private final Connection c;
    private final Clock relogio;
    private final MaquinaDao maquinas;
    private final HistoricoEstadoDao historico;

    public EstadoMaquinaService(Connection c) {
        this(c, Clock.systemDefaultZone());
    }

    public EstadoMaquinaService(Connection c, Clock relogio) {
        this.c = c;
        this.relogio = relogio;
        this.maquinas = new MaquinaDao(c);
        this.historico = new HistoricoEstadoDao(c);
    }

    /** Histórico de uma máquina, do mais recente para o mais antigo. */
    public List<HistoricoEstado> historico(long maquinaId) throws SQLException {
        return historico.listarPorMaquina(maquinaId);
    }

    /** O histórico de todas as máquinas, por máquina e do mais antigo para o mais recente. */
    public List<HistoricoEstado> historicoCompleto() throws SQLException {
        return historico.listarTodos();
    }

    /** Data da última mudança de estado de cada máquina. */
    public Map<Long, LocalDate> desde() throws SQLException {
        return historico.ultimaMudanca();
    }

    /**
     * Muda o estado de uma máquina. O motivo é obrigatório e o novo estado tem de ser diferente do atual.
     * A data não pode ser futura nem anterior à última mudança. Uma máquina que deixa de estar ATIVO sai do
     * itinerário de manutenção; os planos mantêm-se.
     */
    public HistoricoEstado mudarEstado(long maquinaId, EstadoMaquina novo, LocalDate data, String motivo)
            throws SQLException {
        if (novo == null) {
            throw new ValidacaoException("O novo estado é obrigatório.");
        }
        if (data == null) {
            throw new ValidacaoException("A data é obrigatória.");
        }
        Datas.naoFutura(data, LocalDate.now(relogio), "A data");
        if (motivo == null || motivo.isBlank()) {
            throw new ValidacaoException("O motivo é obrigatório.");
        }
        String motivoLimpo = motivo.trim();
        return Tx.call(c, () -> {
            Maquina m = maquinas.porId(maquinaId).orElseThrow(() -> new ValidacaoException("A máquina não existe."));
            if (m.estado() == novo) {
                throw new ValidacaoException("A máquina " + m.codigo() + " já está no estado " + novo + ".");
            }
            // O histórico ordena-se por data: uma mudança anterior à última deixaria o estado atual e o histórico
            // em desacordo.
            historico.listarPorMaquina(maquinaId).stream().findFirst().ifPresent(ultima -> {
                if (data.isBefore(ultima.dataEstado())) {
                    throw new ValidacaoException("A data não pode ser anterior à última mudança de estado ("
                            + Datas.formatar(ultima.dataEstado()) + ").");
                }
            });
            maquinas.atualizarEstado(maquinaId, novo);
            return historico.inserir(new HistoricoEstado(null, maquinaId, data, novo, motivoLimpo));
        });
    }
}
