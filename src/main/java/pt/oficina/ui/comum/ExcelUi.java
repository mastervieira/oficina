package pt.oficina.ui.comum;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.stage.Window;
import pt.oficina.db.Tx;
import pt.oficina.excel.ExcelLeitor;
import pt.oficina.service.FolhaImportada;
import pt.oficina.service.ResultadoImportacao;

/** O fluxo de importar e exportar Excel, comum a máquinas e artigos. */
public final class ExcelUi {

    @FunctionalInterface
    public interface Importador {
        ResultadoImportacao importar(FolhaImportada folha, boolean simular) throws SQLException;
    }

    @FunctionalInterface
    public interface Exportador {
        void exportar(Path destino) throws IOException;
    }

    private ExcelUi() {
    }

    // ---- exportar ------------------------------------------------------------

    /**
     * Exporta as linhas que estão visíveis na tabela (respeita a pesquisa e o filtro).
     *
     * @param verbo "Exportadas" ou "Exportados", conforme o género de {@code entidade}
     */
    public static void exportar(Window dono, String entidade, String verbo, String nomeBase, int visiveis, int total,
            Exportador exportador) {
        Path destino = EscolherFicheiro.guardarXlsx(dono, "Exportar " + entidade + " para Excel",
                nomeBase + "-" + LocalDate.now() + ".xlsx");
        if (destino != null) {
            exportarPara(dono, destino, entidade, verbo, visiveis, total, exportador);
        }
    }

    /**
     * O que se segue a escolher o ficheiro de destino (separado para se poder verificar sem o seletor nativo). O
     * exportador corre fora do fio da interface: deve usar listas já copiadas, não ler tabelas do ecrã.
     */
    public static void exportarPara(Window dono, Path destino, String entidade, String verbo, int visiveis, int total,
            Exportador exportador) {
        try {
            SegundoPlano.executar(dono, "A exportar " + entidade + "…", () -> {
                exportador.exportar(destino);
                return null;
            });
        } catch (IOException | SQLException | RuntimeException e) {
            Dialogos.erro("Não foi possível gravar o ficheiro Excel:\n" + e.getMessage()
                    + "\n\n(Se o ficheiro estiver aberto no Excel, feche-o e tente de novo.)");
            return;
        } catch (OutOfMemoryError e) {
            Dialogos.erro(SEM_MEMORIA);
            return;
        }
        String quantas = visiveis == total
                ? verbo + " " + TextosImportacao.numero(total) + " " + entidade + "."
                : verbo + " " + TextosImportacao.numero(visiveis) + " de " + TextosImportacao.numero(total) + " " + entidade
                        + " (só as que estão na lista, com a pesquisa/filtro atual).";
        Dialogos.info(quantas + "\n\n" + destino);
    }

    // ---- importar ------------------------------------------------------------

    /**
     * 1) lê o ficheiro; 2) simula e mostra o que acontecerá; 3) se o utilizador confirmar, grava. Com erros no
     * ficheiro não se grava nada.
     *
     * @param aoConcluir chamado depois de gravar (para atualizar a lista)
     */
    public static void importar(Window dono, String entidade, Importador importador, Runnable aoConcluir) {
        importar(dono, entidade, importador, aoConcluir, null);
    }

    /** @param rotulos os nomes das contagens; null para o que cria e atualiza (máquinas, artigos) */
    public static void importar(Window dono, String entidade, Importador importador, Runnable aoConcluir,
            TextosImportacao.Rotulos rotulos) {
        Path ficheiro = EscolherFicheiro.abrirXlsx(dono, "Importar " + entidade + " de Excel");
        if (ficheiro != null) {
            importarDe(dono, ficheiro, entidade, importador, aoConcluir, rotulos);
        }
    }

    /** O que se segue a escolher o ficheiro (separado para se poder verificar sem o seletor nativo). */
    public static void importarDe(Window dono, Path ficheiro, String entidade, Importador importador, Runnable aoConcluir) {
        importarDe(dono, ficheiro, entidade, importador, aoConcluir, null);
    }

    public static void importarDe(Window dono, Path ficheiro, String entidade, Importador importador, Runnable aoConcluir,
            TextosImportacao.Rotulos rotulos) {
        try {
            record Lido(FolhaImportada folha, ResultadoImportacao previa) {
            }
            Lido lido = SegundoPlano.executar(dono, "A ler e a verificar " + ficheiro.getFileName() + "…", () -> {
                FolhaImportada f = ExcelLeitor.ler(ficheiro);
                return new Lido(f, importador.importar(f, true));
            });
            FolhaImportada folha = lido.folha();
            ResultadoImportacao previa = lido.previa();
            if (previa.temErros()) {
                mostrarErros(dono, previa);
                return;
            }
            if (!previa.temAlteracoes()) {
                Dialogos.info(TextosImportacao.semNada(previa) + semAvisos(previa));
                return;
            }
            if (!confirmar(dono, "Importar " + entidade, TextosImportacao.previa(previa, ficheiro.getFileName().toString(), rotulos))) {
                return;
            }
            ResultadoImportacao feito = SegundoPlano.executar(dono, "A importar " + entidade + "…",
                    () -> importador.importar(folha, false));
            if (feito.temErros()) { // não devia acontecer depois de uma simulação limpa; nada foi gravado
                mostrarErros(dono, feito);
                return;
            }
            aoConcluir.run();
            Dialogos.info(TextosImportacao.concluido(feito, rotulos));
        } catch (IOException e) {
            Dialogos.erro(e.getMessage());
        } catch (SQLException e) {
            Dialogos.erro(Tx.ligacaoFechada() ? Dialogos.mensagemDeErro(e)
                    : "Erro de base de dados (não foi importado nada):\n" + e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            Dialogos.erro("Erro inesperado (não foi importado nada):\n" + e);
        } catch (OutOfMemoryError e) { // a memória volta a ficar livre: a folha lida deixa de estar referenciada
            Dialogos.erro(SEM_MEMORIA + " Não foi importado nada.");
        }
    }

    private static final String SEM_MEMORIA = "Não há memória suficiente para tratar um ficheiro tão grande. "
            + "Divida-o em vários ficheiros mais pequenos.";

    private static String semAvisos(ResultadoImportacao r) {
        return r.avisos().isEmpty() ? "" : "\n\n" + String.join("\n", r.avisos());
    }

    // ---- diálogos --------------------------------------------------------------

    private static boolean confirmar(Window dono, String titulo, String texto) {
        ButtonType importar = new ButtonType("Importar", ButtonBar.ButtonData.OK_DONE);
        Alert alerta = new Alert(Alert.AlertType.CONFIRMATION, null, importar, ButtonType.CANCEL);
        alerta.initOwner(dono);
        alerta.setTitle(titulo);
        alerta.setHeaderText("Pré-visualização: ainda não foi gravado nada");
        alerta.getDialogPane().setContent(areaDeTexto(texto, 17));
        return alerta.showAndWait().orElse(ButtonType.CANCEL) == importar;
    }

    private static void mostrarErros(Window dono, ResultadoImportacao r) {
        Alert alerta = new Alert(Alert.AlertType.ERROR, null, ButtonType.CLOSE);
        alerta.initOwner(dono);
        alerta.setTitle("Importar");
        alerta.setHeaderText("O ficheiro tem " + TextosImportacao.numero(r.erros().size())
                + (r.erros().size() == 1 ? " erro" : " erros") + ": não foi importado nada.\nCorrija-o e tente de novo.");
        alerta.getDialogPane().setContent(areaDeTexto(TextosImportacao.erros(r), 14));
        alerta.showAndWait();
    }

    private static TextArea areaDeTexto(String texto, int linhas) {
        TextArea area = new TextArea(texto);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(linhas);
        area.setPrefColumnCount(78);
        return area;
    }
}
