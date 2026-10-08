package pt.oficina.ui.comum;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Corre um trabalho demorado (importar ou exportar Excel) fora do fio da interface, com uma janela de espera modal.
 *
 * <p>A janela é modal à aplicação inteira: enquanto o trabalho corre ninguém mexe na interface, por isso a ligação única
 * à base de dados nunca é usada por dois fios ao mesmo tempo. Quem chama continua a escrever código sequencial: o
 * método só volta quando o trabalho acaba (o JavaFX continua a desenhar a janela entretanto, num ciclo de eventos
 * aninhado, tal como num {@code showAndWait}).
 */
public final class SegundoPlano {

    @FunctionalInterface
    public interface Trabalho<T> {
        T executar() throws IOException, SQLException;
    }

    private static volatile boolean ocupado;

    private SegundoPlano() {
    }

    /** Há um trabalho a correr (a janela principal não deve fechar enquanto isso). */
    public static boolean ocupado() {
        return ocupado;
    }

    /**
     * @return o resultado do trabalho; as exceções do trabalho chegam a quem chama tal como foram lançadas
     */
    public static <T> T executar(Window dono, String mensagem, Trabalho<T> trabalho) throws IOException, SQLException {
        if (!Platform.isFxApplicationThread() || !Platform.canStartNestedEventLoop() || ocupado) {
            return trabalho.executar(); // sem interface (ex.: testes), num momento em que o JavaFX não deixa esperar, ou já em curso
        }
        AtomicReference<T> resultado = new AtomicReference<>();
        AtomicReference<Throwable> falha = new AtomicReference<>();
        Object chave = new Object();

        Stage espera = janela(dono, mensagem);
        Thread fio = new Thread(() -> {
            try {
                resultado.set(trabalho.executar());
            } catch (Throwable t) {
                falha.set(t);
            } finally {
                Platform.runLater(() -> Platform.exitNestedEventLoop(chave, null));
            }
        }, "oficina-segundo-plano");
        fio.setDaemon(true);

        ocupado = true;
        try {
            espera.show();
            fio.start();
            Platform.enterNestedEventLoop(chave); // volta quando o fio chamar exitNestedEventLoop
        } finally {
            ocupado = false;
            espera.close();
        }

        Throwable t = falha.get();
        if (t == null) {
            return resultado.get();
        }
        if (t instanceof IOException e) {
            throw e;
        }
        if (t instanceof SQLException e) {
            throw e;
        }
        if (t instanceof RuntimeException e) {
            throw e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        throw new IllegalStateException(t);
    }

    private static Stage janela(Window dono, String mensagem) {
        ProgressIndicator roda = new ProgressIndicator();
        roda.setPrefSize(36, 36);
        HBox conteudo = new HBox(14, roda, new Label(mensagem));
        conteudo.setAlignment(Pos.CENTER_LEFT);
        conteudo.setPadding(new Insets(18, 24, 18, 18));
        Stage s = new Stage(StageStyle.UTILITY);
        if (dono != null) {
            s.initOwner(dono);
        }
        s.initModality(Modality.APPLICATION_MODAL);
        s.setTitle("A trabalhar…");
        s.setResizable(false);
        s.setOnCloseRequest(e -> e.consume()); // não se interrompe a meio: a operação acaba (ou é desfeita) sozinha
        Scene cena = new Scene(conteudo);
        if (dono != null && dono.getScene() != null) {
            cena.getStylesheets().addAll(dono.getScene().getStylesheets());
        }
        s.setScene(cena);
        return s;
    }
}
