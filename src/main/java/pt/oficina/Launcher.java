package pt.oficina;

import javafx.application.Application;
import pt.oficina.ui.comum.ErrosGlobais;

/**
 * Ponto de entrada do JAR. Não estende Application: com o JavaFX no classpath
 * (fat-jar) a main class não pode ser uma subclasse de Application.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        // O POI (Excel) usa o log4j-api sem implementação e imprimiria um erro inofensivo no primeiro uso.
        System.setProperty("log4j2.statusLoggerLevel", "OFF");
        ErrosGlobais.instalar();
        Application.launch(App.class, args);
    }
}
