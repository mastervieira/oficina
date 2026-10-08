package pt.oficina.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pt.oficina.model.MovimentoStock;
import pt.oficina.model.enums.TipoMovimento;

/** Os movimentos só se acrescentam; o stock atual calcula-se aqui, sempre pela soma. */
public final class MovimentoStockDao {

    private static final String SOMA =
            "COALESCE(SUM(CASE m.tipo WHEN 'ENTRADA' THEN m.quantidade ELSE -m.quantidade END), 0)";

    private final Connection c;

    public MovimentoStockDao(Connection c) {
        this.c = c;
    }

    public MovimentoStock inserir(MovimentoStock m) throws SQLException {
        String sql = "INSERT INTO movimento_stock(artigo_id, data_mov, tipo, quantidade, nota) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, m.artigoId());
            Jdbc.setDate(ps, 2, m.dataMov());
            ps.setString(3, m.tipo().name());
            ps.setDouble(4, m.quantidade());
            ps.setString(5, m.nota());
            return new MovimentoStock(Jdbc.executarInsert(ps), m.artigoId(), m.dataMov(), m.tipo(),
                    m.quantidade(), m.nota());
        }
    }

    public double stockAtual(long artigoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + SOMA + " FROM movimento_stock m WHERE m.artigo_id = ?")) {
            ps.setLong(1, artigoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0;
            }
        }
    }

    /** Stock de todos os artigos (inclui os que não têm movimentos, com 0). */
    public Map<Long, Double> stocks() throws SQLException {
        String sql = "SELECT a.id, " + SOMA + " FROM artigo a LEFT JOIN movimento_stock m ON m.artigo_id = a.id "
                + "GROUP BY a.id";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            Map<Long, Double> r = new HashMap<>();
            while (rs.next()) {
                r.put(rs.getLong(1), rs.getDouble(2));
            }
            return r;
        }
    }

    /** Movimentos do artigo (ou de todos se {@code artigoId} for null), do mais recente para o mais antigo. */
    public List<MovimentoStock> listar(Long artigoId) throws SQLException {
        String sql = "SELECT id, artigo_id, data_mov, tipo, quantidade, nota FROM movimento_stock "
                + (artigoId == null ? "" : "WHERE artigo_id = ? ")
                + "ORDER BY data_mov DESC, id DESC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (artigoId != null) {
                ps.setLong(1, artigoId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<MovimentoStock> r = new ArrayList<>();
                while (rs.next()) {
                    r.add(new MovimentoStock(
                            rs.getLong("id"),
                            rs.getLong("artigo_id"),
                            Jdbc.getDate(rs, "data_mov"),
                            TipoMovimento.valueOf(rs.getString("tipo")),
                            rs.getDouble("quantidade"),
                            rs.getString("nota")));
                }
                return r;
            }
        }
    }
}
