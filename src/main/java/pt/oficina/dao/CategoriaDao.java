package pt.oficina.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import pt.oficina.model.Categoria;
import pt.oficina.model.enums.TipoCategoria;

public final class CategoriaDao {

    private final Connection c;

    public CategoriaDao(Connection c) {
        this.c = c;
    }

    private static Categoria mapear(ResultSet rs) throws SQLException {
        return new Categoria(rs.getLong("id"), rs.getString("nome"), TipoCategoria.valueOf(rs.getString("tipo")));
    }

    /** Condição fixa (sem dados do utilizador): categorias de máquinas (tipo MAQUINA) ou de artigos (os restantes). */
    private static String familia(boolean deArtigos) {
        return deArtigos ? "tipo <> 'MAQUINA'" : "tipo = 'MAQUINA'";
    }

    /** Categorias de artigos ({@code deArtigos}) ou de máquinas. */
    public List<Categoria> listar(boolean deArtigos) throws SQLException {
        String sql = "SELECT id, nome, tipo FROM categoria WHERE " + familia(deArtigos)
                + " ORDER BY nome COLLATE NOCASE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                List<Categoria> r = new ArrayList<>();
                while (rs.next()) {
                    r.add(mapear(rs));
                }
                return r;
            }
        }
    }

    public Optional<Categoria> porId(long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, nome, tipo FROM categoria WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        }
    }

    public Categoria inserir(Categoria cat) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO categoria(nome, tipo) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, cat.nome());
            ps.setString(2, cat.tipo().name());
            return new Categoria(Jdbc.executarInsert(ps), cat.nome(), cat.tipo());
        }
    }

    public void atualizarNome(long id, String nome) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE categoria SET nome = ? WHERE id = ?")) {
            ps.setString(1, nome);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * O nome é único dentro da família (todas as categorias de artigos, seja qual for o tipo, ou todas as de
     * máquinas), sem distinguir maiúsculas; {@code excluirId} ignora o próprio registo numa edição.
     */
    public boolean existeNome(String nome, boolean deArtigos, Long excluirId) throws SQLException {
        String sql = "SELECT 1 FROM categoria WHERE nome = ? COLLATE NOCASE AND " + familia(deArtigos)
                + " AND id <> ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, nome);
            ps.setLong(2, excluirId == null ? -1 : excluirId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
