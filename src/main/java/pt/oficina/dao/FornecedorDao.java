package pt.oficina.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import pt.oficina.model.Fornecedor;

public final class FornecedorDao {

    private final Connection c;

    public FornecedorDao(Connection c) {
        this.c = c;
    }

    public List<Fornecedor> listar() throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT id, nome, contacto FROM fornecedor ORDER BY nome COLLATE NOCASE")) {
            List<Fornecedor> r = new ArrayList<>();
            while (rs.next()) {
                r.add(new Fornecedor(rs.getLong("id"), rs.getString("nome"), rs.getString("contacto")));
            }
            return r;
        }
    }

    public Fornecedor inserir(Fornecedor f) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fornecedor(nome, contacto) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, f.nome());
            ps.setString(2, f.contacto());
            return new Fornecedor(Jdbc.executarInsert(ps), f.nome(), f.contacto());
        }
    }

    /** @return false se o fornecedor já não existe */
    public boolean atualizar(Fornecedor f) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE fornecedor SET nome = ?, contacto = ? WHERE id = ?")) {
            ps.setString(1, f.nome());
            ps.setString(2, f.contacto());
            ps.setLong(3, f.id());
            return ps.executeUpdate() > 0;
        }
    }

    public boolean existeNome(String nome, Long excluirId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM fornecedor WHERE nome = ? COLLATE NOCASE AND id <> ?")) {
            ps.setString(1, nome);
            ps.setLong(2, excluirId == null ? -1 : excluirId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
