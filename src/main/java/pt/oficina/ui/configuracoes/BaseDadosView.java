package pt.oficina.ui.configuracoes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import pt.oficina.db.Backups;
import pt.oficina.db.Database;
import pt.oficina.ui.comum.Dialogos;

/** Onde está a base de dados e cópias de segurança (automáticas e manuais). */
final class BaseDadosView extends VBox {

    private final Database db;
    private final Label resumoCopias = new Label();

    BaseDadosView(Database db) {
        super(10);
        this.db = db;
        setPadding(new Insets(15));

        TextField caminho = new TextField(db.ficheiro().toString());
        caminho.setEditable(false);
        TextField pastaCopias = new TextField(Backups.pasta(db).toString());
        pastaCopias.setEditable(false);

        Button copiar = new Button("Criar cópia de segurança…");
        copiar.setOnAction(e -> copiarPara());

        Label nota = new Label("Todos os dados estão neste ficheiro. É feita uma cópia automática por dia, ao abrir a "
                + "aplicação, e ficam guardadas as " + Backups.MANTER_POR_DEFEITO + " mais recentes. Guarde também "
                + "uma cópia fora deste computador.");
        nota.setWrapText(true);

        getChildren().addAll(new Label("Ficheiro da base de dados"), caminho,
                new Label("Cópias automáticas"), pastaCopias, resumoCopias, nota, copiar);
        atualizar();
    }

    void atualizar() {
        try {
            List<Path> copias = Backups.listar(db);
            resumoCopias.setText(copias.isEmpty() ? "Ainda não há cópias automáticas."
                    : copias.size() + " cópia(s); a mais recente: " + copias.get(copias.size() - 1).getFileName()
                            + " (" + Files.size(copias.get(copias.size() - 1)) / 1024 + " KB)");
        } catch (IOException e) {
            resumoCopias.setText("Não foi possível ler a pasta das cópias: " + e.getMessage());
        }
    }

    private void copiarPara() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Guardar cópia de segurança");
        fc.setInitialFileName(Backups.nomeSugerido(db, LocalDateTime.now()));
        File pasta = Backups.pasta(db).toFile();
        fc.setInitialDirectory(pasta.isDirectory() ? pasta : db.ficheiro().getParent().toFile());
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Base de dados SQLite (*.db)", "*.db"));
        File destino = fc.showSaveDialog(getScene().getWindow());
        if (destino != null && Dialogos.tentar(() -> db.copiarPara(destino.toPath()))) {
            Dialogos.info("Cópia gravada em:\n" + destino);
            atualizar();
        }
    }
}
