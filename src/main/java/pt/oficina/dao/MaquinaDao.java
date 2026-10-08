package pt.oficina.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import pt.oficina.model.Maquina;
import pt.oficina.model.enums.EstadoMaquina;

public final class MaquinaDao {

    private static final String COLUNAS =
            "id, codigo, descricao, n_serie, categoria_id, localizacao_id, fornecedor_id, data_aquisicao, estado";

    private final Connection c;

    public MaquinaDao(Connection c) {
        this.c = c;
    }

    private static Maquina mapear(ResultSet rs) throws SQLException {
        return new Maquina(
                rs.getLong("id"),
                rs.getString("codigo"),
                rs.getString("descricao"),
                rs.getString("n_serie"),
                rs.getLong("categoria_id"),
                Jdbc.getLong(rs, "localizacao_id"),
                Jdbc.getLong(rs, "fornecedor_id"),
                Jdbc.getDate(rs, "data_aquisicao"),
                EstadoMaquina.valueOf(rs.getString("estado")));
    }

    public List<Maquina> listar() throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT " + COLUNAS + " FROM maquina ORDER BY codigo COLLATE NOCASE")) {
            List<Maquina> r = new ArrayList<>();
            while (rs.next()) {
                r.add(mapear(rs));
            }
            return r;
        }
    }

    public Optional<Maquina> porId(long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUNAS + " FROM maquina WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        }
    }

    public Maquina inserir(Maquina m) throws SQLException {
        String sql = "INSERT INTO maquina(codigo, descricao, n_serie, categoria_id, localizacao_id, "
                + "fornecedor_id, data_aquisicao, estado) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, m.codigo());
            ps.setString(2, m.descricao());
            ps.setString(3, m.nSerie());
            ps.setLong(4, m.categoriaId());
            Jdbc.setLong(ps, 5, m.localizacaoId());
            Jdbc.setLong(ps, 6, m.fornecedorId());
            Jdbc.setDate(ps, 7, m.dataAquisicao());
            ps.setString(8, m.estado().name());
            long id = Jdbc.executarInsert(ps);
            return new Maquina(id, m.codigo(), m.descricao(), m.nSerie(), m.categoriaId(), m.localizacaoId(),
                    m.fornecedorId(), m.dataAquisicao(), m.estado());
        }
    }

    /** Atualiza os dados de inventário. O estado não se altera aqui: tem histórico próprio. */
    public void atualizar(Maquina m) throws SQLException {
        String sql = "UPDATE maquina SET codigo = ?, descricao = ?, n_serie = ?, categoria_id = ?, "
                + "localizacao_id = ?, fornecedor_id = ?, data_aquisicao = ? WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, m.codigo());
            ps.setString(2, m.descricao());
            ps.setString(3, m.nSerie());
            ps.setLong(4, m.categoriaId());
            Jdbc.setLong(ps, 5, m.localizacaoId());
            Jdbc.setLong(ps, 6, m.fornecedorId());
            Jdbc.setDate(ps, 7, m.dataAquisicao());
            ps.setLong(8, m.id());
            ps.executeUpdate();
        }
    }

    /** Só para uso do serviço de estado, que grava o histórico na mesma transação. */
    public void atualizarEstado(long id, EstadoMaquina estado) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE maquina SET estado = ? WHERE id = ?")) {
            ps.setString(1, estado.name());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** A imagem não vem em {@link #listar()} nem {@link #porId(long)} (pode ser grande): lê-se à parte. */
    public byte[] imagem(long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT imagem FROM maquina WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    /** @param imagem os bytes do ficheiro de imagem, ou null para retirar a imagem */
    public void definirImagem(long id, byte[] imagem) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE maquina SET imagem = ? WHERE id = ?")) {
            ps.setBytes(1, imagem);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void apagar(long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM maquina WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public boolean existeCodigo(String codigo, Long excluirId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM maquina WHERE codigo = ? COLLATE NOCASE AND id <> ?")) {
            ps.setString(1, codigo);
            ps.setLong(2, excluirId == null ? -1 : excluirId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
