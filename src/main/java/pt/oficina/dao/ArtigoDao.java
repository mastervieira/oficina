package pt.oficina.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import pt.oficina.model.Artigo;

public final class ArtigoDao {

    private static final String COLUNAS =
            "id, codigo, descricao, categoria_id, localizacao_id, fornecedor_id, unidade, stock_minimo";

    private final Connection c;

    public ArtigoDao(Connection c) {
        this.c = c;
    }

    private static Artigo mapear(ResultSet rs) throws SQLException {
        return new Artigo(
                rs.getLong("id"),
                rs.getString("codigo"),
                rs.getString("descricao"),
                rs.getLong("categoria_id"),
                Jdbc.getLong(rs, "localizacao_id"),
                Jdbc.getLong(rs, "fornecedor_id"),
                rs.getString("unidade"),
                rs.getDouble("stock_minimo"));
    }

    public List<Artigo> listar() throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT " + COLUNAS + " FROM artigo ORDER BY codigo COLLATE NOCASE")) {
            List<Artigo> r = new ArrayList<>();
            while (rs.next()) {
                r.add(mapear(rs));
            }
            return r;
        }
    }

    public Optional<Artigo> porId(long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUNAS + " FROM artigo WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        }
    }

    public Artigo inserir(Artigo a) throws SQLException {
        String sql = "INSERT INTO artigo(codigo, descricao, categoria_id, localizacao_id, fornecedor_id, "
                + "unidade, stock_minimo) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, a.codigo());
            ps.setString(2, a.descricao());
            ps.setLong(3, a.categoriaId());
            Jdbc.setLong(ps, 4, a.localizacaoId());
            Jdbc.setLong(ps, 5, a.fornecedorId());
            ps.setString(6, a.unidade());
            ps.setDouble(7, a.stockMinimo());
            long id = Jdbc.executarInsert(ps);
            return new Artigo(id, a.codigo(), a.descricao(), a.categoriaId(), a.localizacaoId(),
                    a.fornecedorId(), a.unidade(), a.stockMinimo());
        }
    }

    public void atualizar(Artigo a) throws SQLException {
        String sql = "UPDATE artigo SET codigo = ?, descricao = ?, categoria_id = ?, localizacao_id = ?, "
                + "fornecedor_id = ?, unidade = ?, stock_minimo = ? WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, a.codigo());
            ps.setString(2, a.descricao());
            ps.setLong(3, a.categoriaId());
            Jdbc.setLong(ps, 4, a.localizacaoId());
            Jdbc.setLong(ps, 5, a.fornecedorId());
            ps.setString(6, a.unidade());
            ps.setDouble(7, a.stockMinimo());
            ps.setLong(8, a.id());
            ps.executeUpdate();
        }
    }

    /** A imagem não vem em {@link #listar()} nem {@link #porId(long)} (pode ser grande): lê-se à parte. */
    public byte[] imagem(long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT imagem FROM artigo WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    /** @param imagem os bytes do ficheiro de imagem, ou null para retirar a imagem */
    public void definirImagem(long id, byte[] imagem) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE artigo SET imagem = ? WHERE id = ?")) {
            ps.setBytes(1, imagem);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void apagar(long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM artigo WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public boolean existeCodigo(String codigo, Long excluirId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM artigo WHERE codigo = ? COLLATE NOCASE AND id <> ?")) {
            ps.setString(1, codigo);
            ps.setLong(2, excluirId == null ? -1 : excluirId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
