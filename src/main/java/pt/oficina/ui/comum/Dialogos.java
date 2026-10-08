package pt.oficina.ui.comum;

import java.sql.SQLException;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import pt.oficina.db.Tx;
import pt.oficina.service.ValidacaoException;

public final class Dialogos {

    private Dialogos() {
    }

    public static void erro(String mensagem) {
        Alert a = new Alert(Alert.AlertType.ERROR, mensagem, ButtonType.OK);
        a.setHeaderText(null);
        a.showAndWait();
    }

    /** Pergunta Sim/Não ao utilizador. */
    public static boolean confirmar(String mensagem) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, mensagem, ButtonType.YES, ButtonType.NO);
        a.setHeaderText(null);
        return a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    public static void info(String mensagem) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, mensagem, ButtonType.OK);
        a.setHeaderText(null);
        a.showAndWait();
    }

    /** Executa a ação e mostra o erro (validação, BD ou inesperado) ao utilizador. Devolve true se correu sem erros. */
    public static boolean tentar(Tx.VoidWork acao) {
        try {
            acao.run();
            return true;
        } catch (SQLException | RuntimeException e) {
            erro(mensagemDeErro(e));
        }
        return false;
    }

    /**
     * O texto a mostrar para um erro: o da regra de negócio tal como vem, o da BD com um prefixo, e os inesperados
     * (por exemplo, dados inválidos lidos da BD ou um defeito da aplicação) descritos e registados no terminal.
     */
    public static String mensagemDeErro(Exception e) {
        if (Tx.ligacaoFechada()) {
            return LIGACAO_FECHADA;
        }
        if (e instanceof ValidacaoException) {
            return e.getMessage();
        }
        if (e instanceof SQLException) {
            return "Erro de base de dados:\n" + e.getMessage();
        }
        e.printStackTrace();
        return ErrosGlobais.descrever(e);
    }

    private static final String LIGACAO_FECHADA = "A ligação à base de dados foi fechada depois de uma falha grave. "
            + "Nada ficou gravado a meio, mas a aplicação não consegue continuar: feche-a e volte a abri-la.";
}
