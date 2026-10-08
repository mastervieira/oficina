package pt.oficina;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import pt.oficina.db.Backups;
import pt.oficina.db.Database;
import pt.oficina.db.PastaDeDados;
import pt.oficina.service.EstadoMaquinaService;
import pt.oficina.service.ImportacaoService;
import pt.oficina.service.InventarioService;
import pt.oficina.service.ManutencaoService;
import pt.oficina.service.StockService;
import pt.oficina.ui.MainView;
import pt.oficina.ui.comum.Dialogos;
import pt.oficina.ui.comum.SegundoPlano;

public class App extends Application {

    private Database database;

    @Override
    public void start(Stage stage) {
        Path ficheiro = Database.defaultPath();
        try {
            database = Database.open(ficheiro);
        } catch (SQLException e) {
            new Alert(Alert.AlertType.ERROR,
                    "Não foi possível abrir a base de dados:\n" + ficheiro + "\n\n" + e.getMessage()).showAndWait();
            return;
        }
        // Cópia automática (uma por dia). Uma falha não impede o uso da aplicação, mas não pode passar em silêncio.
        String avisoCopia = null;
        try {
            Backups.automatica(database, LocalDateTime.now(), Backups.MANTER_POR_DEFEITO);
        } catch (SQLException | RuntimeException e) {
            e.printStackTrace();
            avisoCopia = "A cópia de segurança automática falhou:\n" + e.getMessage()
                    + "\n\nA aplicação funciona normalmente, mas faça uma cópia manual em Configurações.";
        }

        InventarioService inventario = new InventarioService(database.connection());
        StockService stock = new StockService(database.connection());
        ManutencaoService manutencao = new ManutencaoService(database.connection(), stock);
        EstadoMaquinaService estados = new EstadoMaquinaService(database.connection());
        ImportacaoService importacao = new ImportacaoService(database.connection());

        stage.setTitle("Oficina " + Versao.atual());
        // Fechar a meio de uma importação fecharia a base de dados com a transação a correr noutro fio.
        stage.setOnCloseRequest(e -> {
            if (SegundoPlano.ocupado()) {
                e.consume();
            }
        });
        stage.setScene(new Scene(new MainView(inventario, stock, manutencao, estados, importacao, database), 1150, 680));
        stage.show();
        if (avisoCopia != null) {
            Dialogos.erro(avisoCopia);
        }
        Path antiga = PastaDeDados.migradaDe();
        if (antiga != null) {
            Dialogos.info("Os dados passaram a ficar na pasta do utilizador, para sobreviverem às atualizações:\n"
                    + ficheiro.getParent() + "\n\nFoi feita uma cópia da base de dados e das cópias de segurança que estavam em:\n"
                    + antiga.getParent() + "\n\nEssa pasta não foi alterada nem apagada; pode eliminá-la quando confirmar que está tudo certo.");
        }
    }

    @Override
    public void stop() throws SQLException {
        if (database != null) {
            database.close();
        }
    }
}
