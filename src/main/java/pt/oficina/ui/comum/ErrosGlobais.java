package pt.oficina.ui.comum;

import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

/** Mostra ao utilizador as exceções que ninguém tratou, em vez de ficarem só no terminal. */
public final class ErrosGlobais {

    private static final int MAX_MENSAGEM = 400;
    private static final AtomicBoolean aMostrar = new AtomicBoolean(false);

    private ErrosGlobais() {
    }

    /** Chamar uma vez, antes de arrancar o JavaFX. */
    public static void instalar() {
        Thread.setDefaultUncaughtExceptionHandler((thread, erro) -> {
            erro.printStackTrace();
            mostrar(erro);
        });
    }

    static String descrever(Throwable erro) {
        String mensagem = String.valueOf(erro.getMessage());
        if (mensagem.length() > MAX_MENSAGEM) {
            mensagem = mensagem.substring(0, MAX_MENSAGEM) + "…";
        }
        return "Ocorreu um erro inesperado e a operação pode não ter sido concluída. As alterações a meio são "
                + "desfeitas.\n\n" + erro.getClass().getSimpleName() + ": " + mensagem;
    }

    private static void mostrar(Throwable erro) {
        if (!aMostrar.compareAndSet(false, true)) {
            return; // já há um aviso aberto: evita uma pilha de janelas se o erro se repetir
        }
        Runnable abrir = () -> {
            try {
                Alert a = new Alert(Alert.AlertType.ERROR, descrever(erro), ButtonType.OK);
                a.setHeaderText(null);
                a.setOnHidden(e -> aMostrar.set(false));
                a.show(); // não bloqueia: showAndWait não é permitido durante o layout/animações
            } catch (RuntimeException e) {
                aMostrar.set(false);
                e.printStackTrace();
            }
        };
        try {
            if (Platform.isFxApplicationThread()) {
                abrir.run();
            } else {
                Platform.runLater(abrir);
            }
        } catch (IllegalStateException e) { // JavaFX ainda não arrancou ou já terminou
            aMostrar.set(false);
        }
    }
}
