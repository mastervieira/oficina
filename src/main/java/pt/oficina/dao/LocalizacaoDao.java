package pt.oficina.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import pt.oficina.model.Localizacao;

public final class LocalizacaoDao {

    private final Connection c;

    public LocalizacaoDao(Connection c) {
        this.c = c;
    }

    public List<Localizacao> listar() throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT id, nome FROM localizacao ORDER BY nome COLLATE NOCASE")) {
            List<Localizacao> r = new ArrayList<>();
            while (rs.next()) {
                r.add(new Localizacao(rs.getLong("id"), rs.getString("nome")));
            }
            return r;
        }
    }

    public Localizacao inserir(Localizacao l) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO localizacao(nome) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, l.nome());
            return new Localizacao(Jdbc.executarInsert(ps), l.nome());
        }
    }

    /** @return false se a localização já não existe */
    public boolean atualizarNome(long id, String nome) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE localizacao SET nome = ? WHERE id = ?")) {
            ps.setString(1, nome);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean existeNome(String nome, Long excluirId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM localizacao WHERE nome = ? COLLATE NOCASE AND id <> ?")) {
            ps.setString(1, nome);
            ps.setLong(2, excluirId == null ? -1 : excluirId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
