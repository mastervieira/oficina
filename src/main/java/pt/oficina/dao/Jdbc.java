package pt.oficina.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;

/** Conversões repetidas nos DAOs: NULL em colunas numéricas, datas ISO e chave gerada. */
final class Jdbc {

    private Jdbc() {
    }

    static Long getLong(ResultSet rs, String coluna) throws SQLException {
        long v = rs.getLong(coluna);
        return rs.wasNull() ? null : v;
    }

    /** null se a coluna é NULL (o wasNull() só vale para a última coluna lida, por isso lê-se aqui logo a seguir). */
    static Double getDouble(ResultSet rs, String coluna) throws SQLException {
        double v = rs.getDouble(coluna);
        return rs.wasNull() ? null : v;
    }

    static LocalDate getDate(ResultSet rs, String coluna) throws SQLException {
        String s = rs.getString(coluna);
        return s == null ? null : LocalDate.parse(s);
    }

    static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setLong(i, v);
        }
    }

    static void setDate(PreparedStatement ps, int i, LocalDate d) throws SQLException {
        if (d == null) {
            ps.setNull(i, Types.VARCHAR);
        } else {
            ps.setString(i, d.toString());
        }
    }

    /** Executa o INSERT e devolve o id gerado. O statement tem de ser criado com RETURN_GENERATED_KEYS. */
    static long executarInsert(PreparedStatement ps) throws SQLException {
        ps.executeUpdate();
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("INSERT sem chave gerada");
            }
            return keys.getLong(1);
        }
    }
}
