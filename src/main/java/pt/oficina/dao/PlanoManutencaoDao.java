package pt.oficina.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import pt.oficina.model.PlanoManutencao;

public final class PlanoManutencaoDao {

    private static final String COLUNAS = "id, categoria_id, tarefa, periodicidade_meses";

    private final Connection c;

    public PlanoManutencaoDao(Connection c) {
        this.c = c;
    }

    private static PlanoManutencao mapear(ResultSet rs) throws SQLException {
        return new PlanoManutencao(rs.getLong("id"), rs.getLong("categoria_id"), rs.getString("tarefa"),
                rs.getInt("periodicidade_meses"));
    }

    public List<PlanoManutencao> listar() throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT " + COLUNAS
                        + " FROM plano_manutencao ORDER BY categoria_id, tarefa COLLATE NOCASE")) {
            List<PlanoManutencao> r = new ArrayList<>();
            while (rs.next()) {
                r.add(mapear(rs));
            }
            return r;
        }
    }

    public List<PlanoManutencao> listarPorCategoria(long categoriaId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUNAS
                + " FROM plano_manutencao WHERE categoria_id = ? ORDER BY tarefa COLLATE NOCASE")) {
            ps.setLong(1, categoriaId);
            try (ResultSet rs = ps.executeQuery()) {
                List<PlanoManutencao> r = new ArrayList<>();
                while (rs.next()) {
                    r.add(mapear(rs));
                }
                return r;
            }
        }
    }

    public Optional<PlanoManutencao> porId(long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUNAS + " FROM plano_manutencao WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        }
    }

    public PlanoManutencao inserir(PlanoManutencao p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO plano_manutencao(categoria_id, tarefa, periodicidade_meses) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, p.categoriaId());
            ps.setString(2, p.tarefa());
            ps.setInt(3, p.periodicidadeMeses());
            return new PlanoManutencao(Jdbc.executarInsert(ps), p.categoriaId(), p.tarefa(), p.periodicidadeMeses());
        }
    }

    /** Altera a tarefa e a periodicidade; o tipo de máquina de um plano não muda. */
    public void atualizar(PlanoManutencao p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE plano_manutencao SET tarefa = ?, periodicidade_meses = ? WHERE id = ?")) {
            ps.setString(1, p.tarefa());
            ps.setInt(2, p.periodicidadeMeses());
            ps.setLong(3, p.id());
            ps.executeUpdate();
        }
    }

    public void apagar(long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM plano_manutencao WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }
}
