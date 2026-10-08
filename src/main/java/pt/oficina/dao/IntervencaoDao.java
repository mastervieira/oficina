package pt.oficina.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pt.oficina.model.Intervencao;
import pt.oficina.model.enums.TipoIntervencao;

/** As intervenções só se acrescentam. */
public final class IntervencaoDao {

    private final Connection c;

    public IntervencaoDao(Connection c) {
        this.c = c;
    }

    public Intervencao inserir(Intervencao i) throws SQLException {
        String sql = "INSERT INTO intervencao(maquina_id, plano_id, data_interv, tipo, descricao, custo) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, i.maquinaId());
            Jdbc.setLong(ps, 2, i.planoId());
            Jdbc.setDate(ps, 3, i.dataInterv());
            ps.setString(4, i.tipo().name());
            ps.setString(5, i.descricao());
            if (i.custo() == null) {
                ps.setNull(6, Types.REAL);
            } else {
                ps.setDouble(6, i.custo());
            }
            return new Intervencao(Jdbc.executarInsert(ps), i.maquinaId(), i.planoId(), i.dataInterv(), i.tipo(),
                    i.descricao(), i.custo());
        }
    }

    /** Todas as intervenções, da mais recente para a mais antiga. */
    public List<Intervencao> listar() throws SQLException {
        String sql = "SELECT id, maquina_id, plano_id, data_interv, tipo, descricao, custo FROM intervencao "
                + "ORDER BY data_interv DESC, id DESC";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            List<Intervencao> r = new ArrayList<>();
            while (rs.next()) {
                r.add(new Intervencao(
                        rs.getLong("id"),
                        rs.getLong("maquina_id"),
                        Jdbc.getLong(rs, "plano_id"),
                        Jdbc.getDate(rs, "data_interv"),
                        TipoIntervencao.valueOf(rs.getString("tipo")),
                        rs.getString("descricao"),
                        Jdbc.getDouble(rs, "custo")));
            }
            return r;
        }
    }

    /** Uma máquina e um plano: a próxima intervenção conta-se por máquina. */
    public record MaquinaPlano(long maquinaId, long planoId) {
    }

    /** Data da última intervenção de cada máquina ligada a cada plano (os pares sem intervenções não aparecem). */
    public Map<MaquinaPlano, LocalDate> ultimaPorMaquinaEPlano() throws SQLException {
        String sql = "SELECT maquina_id, plano_id, MAX(data_interv) AS ultima FROM intervencao "
                + "WHERE plano_id IS NOT NULL GROUP BY maquina_id, plano_id";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            Map<MaquinaPlano, LocalDate> r = new HashMap<>();
            while (rs.next()) {
                r.put(new MaquinaPlano(rs.getLong("maquina_id"), rs.getLong("plano_id")),
                        LocalDate.parse(rs.getString("ultima")));
            }
            return r;
        }
    }

    public boolean existeParaPlano(long planoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM intervencao WHERE plano_id = ? LIMIT 1")) {
            ps.setLong(1, planoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
