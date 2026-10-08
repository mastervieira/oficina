package pt.oficina.ui.comum;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** Diálogos de abrir/guardar ficheiros (Excel e imagens). Lembra a última pasta usada durante a sessão. */
public final class EscolherFicheiro {

    private static final FileChooser.ExtensionFilter EXCEL =
            new FileChooser.ExtensionFilter("Livro do Excel (*.xlsx)", "*.xlsx");

    private static final FileChooser.ExtensionFilter IMAGENS = new FileChooser.ExtensionFilter(
            "Imagens (*.png, *.jpg, *.jpeg, *.gif, *.bmp)", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp");

    private static File ultimaPasta;

    private EscolherFicheiro() {
    }

    /** @return o ficheiro escolhido, ou null se o utilizador cancelou */
    public static Path abrirXlsx(Window dono, String titulo) {
        FileChooser fc = preparar(titulo);
        File escolhido = fc.showOpenDialog(dono);
        return lembrar(escolhido);
    }

    /** @return a imagem escolhida, ou null se o utilizador cancelou */
    public static Path abrirImagem(Window dono, String titulo) {
        FileChooser fc = preparar(titulo, IMAGENS);
        return lembrar(fc.showOpenDialog(dono));
    }

    /** @return o ficheiro escolhido (com a extensão .xlsx garantida), ou null se o utilizador cancelou */
    public static Path guardarXlsx(Window dono, String titulo, String nomeSugerido) {
        FileChooser fc = preparar(titulo);
        fc.setInitialFileName(nomeSugerido);
        File escolhido = fc.showSaveDialog(dono);
        if (escolhido != null && !escolhido.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            escolhido = new File(escolhido.getParentFile(), escolhido.getName() + ".xlsx");
        }
        return lembrar(escolhido);
    }

    private static FileChooser preparar(String titulo) {
        return preparar(titulo, EXCEL);
    }

    private static FileChooser preparar(String titulo, FileChooser.ExtensionFilter filtro) {
        FileChooser fc = new FileChooser();
        fc.setTitle(titulo);
        fc.getExtensionFilters().add(filtro);
        if (ultimaPasta != null && ultimaPasta.isDirectory()) {
            fc.setInitialDirectory(ultimaPasta);
        }
        return fc;
    }

    private static Path lembrar(File escolhido) {
        if (escolhido == null) {
            return null;
        }
        ultimaPasta = escolhido.getParentFile();
        return escolhido.toPath();
    }
}
