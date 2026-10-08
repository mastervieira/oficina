package pt.oficina.ui.comum;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import pt.oficina.service.Imagens;
import pt.oficina.service.ValidacaoException;

/**
 * Campo "Imagem" (foto) de um formulário: pré-visualização, "Escolher imagem…" e "Remover". Só conta como alterado
 * se o utilizador escolheu ou removeu uma imagem; senão, ao gravar, fica a que o registo já tinha. Os erros (ficheiro
 * grande demais, que não é uma imagem, ...) escrevem-se no rótulo de erro do formulário, sem caminhos de ficheiros.
 *
 * <p>Ler e descodificar a imagem corre fora do fio da interface ({@link SegundoPlano}), com tempo limite, e a
 * descodificação é feita por {@link Imagens} (nunca pelos descodificadores nativos do JavaFX) numa miniatura.
 */
public final class CampoImagem {

    private static final double PREVIEW_LARGURA = 320;
    private static final double PREVIEW_ALTURA = 220;

    private record Aberta(byte[] bytes, BufferedImage miniatura) {
    }

    private final Label erro;
    private final Label semImagem = new Label("Sem imagem");
    private final ImageView previa = new ImageView();
    private final Button remover = new Button("Remover");
    private final VBox no;
    private byte[] imagem;
    private Image miniatura;
    private boolean falhou;
    private boolean alterada;

    /** @param tituloEscolher título da janela de escolha do ficheiro, ex.: "Escolher a imagem do artigo" */
    public CampoImagem(String tituloEscolher, Label erro) {
        this.erro = erro;
        semImagem.setStyle("-fx-text-fill: #777777;");
        previa.setPreserveRatio(true);
        previa.setFitWidth(PREVIEW_LARGURA);
        previa.setFitHeight(PREVIEW_ALTURA);
        StackPane moldura = new StackPane(semImagem, previa);
        moldura.setPrefSize(PREVIEW_LARGURA, PREVIEW_ALTURA);
        moldura.setMaxSize(PREVIEW_LARGURA, PREVIEW_ALTURA);
        moldura.setStyle("-fx-border-color: #bbbbbb; -fx-background-color: #fafafa;");
        Button escolher = new Button("Escolher imagem…");
        escolher.setOnAction(e -> {
            Path ficheiro = EscolherFicheiro.abrirImagem(janela(), tituloEscolher);
            if (ficheiro != null) {
                escolher(ficheiro);
            }
        });
        remover.setOnAction(e -> {
            imagem = null;
            miniatura = null;
            falhou = false;
            alterada = true;
            mostrar();
        });
        no = new VBox(6, moldura, new HBox(8, escolher, remover));
        no.setAlignment(Pos.TOP_LEFT);
        mostrar();
    }

    public Node no() {
        return no;
    }

    /**
     * Mostra a imagem que o registo já tem (null: nenhuma). Não conta como alteração. A pré-visualização só se
     * descodifica quando o formulário já está aberto, e uma imagem que não se consiga abrir não impede de gravar o
     * resto (e a imagem fica como estava).
     */
    public void mostrarInicial(byte[] bytes) {
        imagem = bytes;
        miniatura = null;
        falhou = false;
        mostrar();
        if (bytes != null) {
            Platform.runLater(() -> {
                if (imagem != bytes) {
                    return; // entretanto o utilizador escolheu ou removeu a imagem
                }
                try {
                    miniatura = paraFx(abrir(() -> new Aberta(bytes, Imagens.miniatura(bytes,
                            (int) PREVIEW_LARGURA, (int) PREVIEW_ALTURA, Imagens.LIMITE_DESCODIFICAR))).miniatura());
                } catch (RuntimeException e) {
                    falhou = true;
                }
                mostrar();
            });
        }
    }

    /** @return true se o utilizador escolheu ou removeu a imagem */
    public boolean alterada() {
        return alterada;
    }

    /** @return os bytes do ficheiro de imagem, ou null se não há imagem */
    public byte[] imagem() {
        return imagem;
    }

    private void escolher(Path ficheiro) {
        try {
            Aberta aberta = abrir(() -> {
                byte[] bytes = Imagens.lerFicheiro(ficheiro);
                return new Aberta(bytes, Imagens.miniatura(bytes, (int) PREVIEW_LARGURA, (int) PREVIEW_ALTURA,
                        Imagens.LIMITE_DESCODIFICAR));
            });
            imagem = aberta.bytes();
            miniatura = paraFx(aberta.miniatura());
            falhou = false;
            alterada = true;
            erro.setText("");
            mostrar();
        } catch (ValidacaoException e) {
            erro.setText(e.getMessage());
        } catch (RuntimeException e) {
            erro.setText("Não foi possível abrir a imagem.");
        }
    }

    private Aberta abrir(SegundoPlano.Trabalho<Aberta> trabalho) {
        try {
            return SegundoPlano.executar(janela(), "A abrir a imagem…", trabalho);
        } catch (IOException | SQLException e) { // o trabalho não os lança; ficam como falha genérica
            throw new ValidacaoException("Não foi possível abrir a imagem.");
        }
    }

    private Window janela() {
        return no.getScene() == null ? null : no.getScene().getWindow();
    }

    private void mostrar() {
        previa.setImage(miniatura);
        semImagem.setText(falhou ? "Não é possível mostrar a imagem" : "Sem imagem");
        semImagem.setVisible(miniatura == null);
        remover.setDisable(imagem == null);
    }

    /** A miniatura (já pequena) como imagem do JavaFX, copiando os píxeis: o JavaFX não volta a descodificar nada. */
    private static Image paraFx(BufferedImage bi) {
        int l = bi.getWidth();
        int a = bi.getHeight();
        WritableImage img = new WritableImage(l, a);
        img.getPixelWriter().setPixels(0, 0, l, a, PixelFormat.getIntArgbInstance(), bi.getRGB(0, 0, l, a, null, 0, l), 0, l);
        return img;
    }
}
