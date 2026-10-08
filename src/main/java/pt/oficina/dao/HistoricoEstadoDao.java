package pt.oficina.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pt.oficina.model.HistoricoEstado;
import pt.oficina.model.enums.EstadoMaquina;

/**
 * O histórico só se acrescenta: os triggers do esquema impedem qualquer UPDATE e só deixam apagar a linha única
 * de uma máquina (o registo inicial de uma máquina que nunca mudou de estado).
 */
public final class HistoricoEstadoDao {

    private final Connection c;

    public HistoricoEstadoDao(Connection c) {
        this.c = c;
    }

    public HistoricoEstado inserir(HistoricoEstado h) throws SQLException {
        String sql = "INSERT INTO historico_estado(maquina_id, data_estado, estado, motivo) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, h.maquinaId());
            Jdbc.setDate(ps, 2, h.dataEstado());
            ps.setString(3, h.estado().name());
            ps.setString(4, h.motivo());
            return new HistoricoEstado(Jdbc.executarInsert(ps), h.maquinaId(), h.dataEstado(), h.estado(), h.motivo());
        }
    }

    /** Apaga o histórico de uma máquina. O trigger só o permite se for uma única linha (o registo inicial). */
    public void apagarDaMaquina(long maquinaId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM historico_estado WHERE maquina_id = ?")) {
            ps.setLong(1, maquinaId);
            ps.executeUpdate();
        }
    }

    /** Histórico de uma máquina, do mais recente para o mais antigo. */
    public List<HistoricoEstado> listarPorMaquina(long maquinaId) throws SQLException {
        String sql = "SELECT id, maquina_id, data_estado, estado, motivo FROM historico_estado "
                + "WHERE maquina_id = ? ORDER BY data_estado DESC, id DESC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, maquinaId);
            try (ResultSet rs = ps.executeQuery()) {
                List<HistoricoEstado> r = new ArrayList<>();
                while (rs.next()) {
                    r.add(new HistoricoEstado(
                            rs.getLong("id"),
                            rs.getLong("maquina_id"),
                            Jdbc.getDate(rs, "data_estado"),
                            EstadoMaquina.valueOf(rs.getString("estado")),
                            rs.getString("motivo")));
                }
                return r;
            }
        }
    }

    /** O histórico de todas as máquinas, por máquina e do mais antigo para o mais recente. */
    public List<HistoricoEstado> listarTodos() throws SQLException {
        String sql = "SELECT id, maquina_id, data_estado, estado, motivo FROM historico_estado "
                + "ORDER BY maquina_id, data_estado, id";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            List<HistoricoEstado> r = new ArrayList<>();
            while (rs.next()) {
                r.add(new HistoricoEstado(rs.getLong("id"), rs.getLong("maquina_id"), Jdbc.getDate(rs, "data_estado"),
                        EstadoMaquina.valueOf(rs.getString("estado")), rs.getString("motivo")));
            }
            return r;
        }
    }

    /** Data da última mudança de estado de cada máquina. */
    public Map<Long, LocalDate> ultimaMudanca() throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT maquina_id, MAX(data_estado) AS ultima FROM historico_estado GROUP BY maquina_id")) {
            Map<Long, LocalDate> r = new HashMap<>();
            while (rs.next()) {
                r.put(rs.getLong("maquina_id"), LocalDate.parse(rs.getString("ultima")));
            }
            return r;
        }
    }
}
