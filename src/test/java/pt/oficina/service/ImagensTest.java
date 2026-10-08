package pt.oficina.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Imagens escolhidas pelo utilizador são ficheiros não fiáveis: ver {@link Imagens}. */
class ImagensTest {

    private static final Duration LIMITE = Duration.ofSeconds(20);

    @TempDir
    Path tmp;

    // ---- construção de imagens -------------------------------------------

    private static byte[] imagem(int largura, int altura, int tipo, String formato) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(largura, altura, tipo), formato, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] png(int l, int a) {
        return imagem(l, a, BufferedImage.TYPE_INT_RGB, "png");
    }

    /** Só a assinatura e o IHDR de um PNG: poucos bytes que anunciam {@code largura} x {@code altura} píxeis. */
    private static byte[] cabecalhoPng(int largura, int altura) {
        ByteBuffer ihdr = ByteBuffer.allocate(17).put("IHDR".getBytes()).putInt(largura).putInt(altura)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        return ByteBuffer.allocate(8 + 4 + 17 + 4)
                .put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})
                .putInt(13).put(ihdr.array()).putInt((int) crc.getValue()).array();
    }

    /** Um GIF de 1x1 píxel com {@code frames} frames (cada um com os seus dados LZW mínimos). */
    private static byte[] gif(int frames) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("GIF89a".getBytes(StandardCharsets.US_ASCII));
        o.writeBytes(new byte[] {1, 0, 1, 0, (byte) 0x80, 0, 0}); // ecrã 1x1, tabela global de 2 cores
        o.writeBytes(new byte[] {0, 0, 0, (byte) 255, (byte) 255, (byte) 255});
        for (int i = 0; i < frames; i++) {
            o.writeBytes(new byte[] {0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 0x01, 0});
        }
        o.write(0x3B);
        return o.toByteArray();
    }

    private Path escrever(String nome, byte[] bytes) throws IOException {
        Path f = tmp.resolve(nome);
        Files.write(f, bytes);
        return f;
    }

    // ---- assinatura, não extensão ----------------------------------------

    @Test
    void aExtensaoNaoContaSoOConteudo() throws IOException {
        Path texto = escrever("foto.png", "isto não é um PNG".getBytes());
        assertThrows(ValidacaoException.class, () -> Imagens.validar(Imagens.lerFicheiro(texto)));

        Path verdadeira = escrever("foto.txt", png(3, 2));
        Imagens.validar(Imagens.lerFicheiro(verdadeira)); // PNG a sério com extensão errada: aceite
    }

    @Test
    void svgEOutrosFormatosSaoRecusados() {
        assertThrows(ValidacaoException.class, () -> Imagens.validar("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes()));
        // WebP e TIFF (cabeçalhos reais): fora da lista
        byte[] webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(ValidacaoException.class, () -> Imagens.validar(webp));
        assertThrows(ValidacaoException.class, () -> Imagens.validar(new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0}));
    }

    @Test
    void aceitaOsQuatroFormatosDaListaBranca() {
        Imagens.validar(png(4, 3));
        Imagens.validar(imagem(4, 3, BufferedImage.TYPE_INT_RGB, "jpeg"));
        Imagens.validar(imagem(4, 3, BufferedImage.TYPE_INT_RGB, "bmp"));
        Imagens.validar(gif(1));
    }

    // ---- limites ---------------------------------------------------------

    @Test
    void ficheiroVazioRecusado() throws IOException {
        Path vazio = escrever("vazio.png", new byte[0]);
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> Imagens.validar(Imagens.lerFicheiro(vazio)));
        assertTrue(e.getMessage().contains("vazia"), e.getMessage());
    }

    @Test
    void acimaDoLimiteDeBytesRecusadoSemLerOFicheiro() throws IOException {
        Path grande = tmp.resolve("grande.png");
        try (RandomAccessFile f = new RandomAccessFile(grande.toFile(), "rw")) {
            f.setLength(Imagens.MAX_BYTES + 1L); // ficheiro esparso: não ocupa disco nem memória
        }
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> Imagens.lerFicheiro(grande));
        assertTrue(e.getMessage().contains("demasiado grande"), e.getMessage());
        assertThrows(ValidacaoException.class, () -> Imagens.validar(new byte[Imagens.MAX_BYTES + 1]));
    }

    @Test
    void dimensoesExcessivasRecusadasSemDescodificar() {
        byte[] bomba = cabecalhoPng(30_000, 30_000);
        assertTrue(bomba.length < 100);
        ValidacaoException e = assertThrows(ValidacaoException.class,
                () -> Imagens.miniatura(bomba, 320, 220, LIMITE));
        assertTrue(e.getMessage().contains("30000 x 30000"), e.getMessage());
        assertThrows(ValidacaoException.class, () -> Imagens.validar(cabecalhoPng(9_000, 9_000))); // 81 MP
        Imagens.validar(cabecalhoPng(Imagens.MAX_LADO, 5_000)); // no limite (50 MP): aceite
    }

    @Test
    void gifComFramesDemaisRecusado() {
        Imagens.validar(gif(Imagens.MAX_FRAMES));
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> Imagens.validar(gif(Imagens.MAX_FRAMES + 1)));
        assertTrue(e.getMessage().contains("frames"), e.getMessage());
    }

    // ---- ficheiros corrompidos -------------------------------------------

    @Test
    void ficheiroCorrompidoNaoRebentaEDaMensagemSemDetalhes() {
        byte[] inteiro = png(200, 200);
        byte[] truncado = Arrays.copyOf(inteiro, inteiro.length / 2);
        // o cabeçalho está bom, os dados não: só se descobre ao descodificar
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> Imagens.miniatura(truncado, 320, 220, LIMITE));
        assertFalse(e.getMessage().contains("Exception"), e.getMessage());

        byte[] lixo = new byte[200];
        lixo[0] = (byte) 0xFF;
        lixo[1] = (byte) 0xD8;
        lixo[2] = (byte) 0xFF; // parece JPEG, é lixo
        assertThrows(ValidacaoException.class, () -> Imagens.validar(lixo));
        assertThrows(ValidacaoException.class, () -> Imagens.miniatura(lixo, 320, 220, LIMITE));
    }

    // ---- ficheiros do sistema de ficheiros -------------------------------

    @Test
    void linkSimbolicoParaImagemLeSeEParaOutraCoisaRecusaSe() throws IOException {
        Path real = escrever("real.png", png(3, 3));
        Path link = Files.createSymbolicLink(tmp.resolve("atalho.png"), real);
        assertArrayEquals(Files.readAllBytes(real), Imagens.lerFicheiro(link));

        Path dir = Files.createDirectory(tmp.resolve("pasta"));
        Path linkParaPasta = Files.createSymbolicLink(tmp.resolve("atalho-pasta.png"), dir);
        assertThrows(ValidacaoException.class, () -> Imagens.lerFicheiro(linkParaPasta));

        Path linkParaTexto = Files.createSymbolicLink(tmp.resolve("passwd.png"), escrever("segredo.txt", "x".getBytes()));
        assertThrows(ValidacaoException.class, () -> Imagens.validar(Imagens.lerFicheiro(linkParaTexto)));
    }

    @Test
    void ficheiroInexistenteOuPastaDaMensagemSemCaminho() {
        Path inexistente = tmp.resolve("nao-existe-nesta-pasta-xyz.png");
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> Imagens.lerFicheiro(inexistente));
        assertFalse(e.getMessage().contains("xyz"), e.getMessage());
        assertFalse(e.getMessage().contains(tmp.toString()), e.getMessage());
        assertThrows(ValidacaoException.class, () -> Imagens.lerFicheiro(tmp));
    }

    // ---- descodificação --------------------------------------------------

    @Test
    void miniaturaUsaSubamostragemEPoupaMemoria() {
        BufferedImage m = Imagens.miniatura(png(4_000, 3_000), 320, 220, LIMITE);
        assertNotNull(m);
        assertTrue(m.getWidth() >= 320 && m.getHeight() >= 220, m.getWidth() + "x" + m.getHeight());
        assertTrue(m.getWidth() <= 640 && m.getHeight() <= 480, "não descodificou a resolução total: "
                + m.getWidth() + "x" + m.getHeight());
        // uma imagem pequena não se aumenta nem se estraga
        BufferedImage pequena = Imagens.miniatura(png(30, 20), 320, 220, LIMITE);
        assertEquals(30, pequena.getWidth());
    }

    @Test
    void tempoLimiteAbortaEOServicoContinuaAFuncionar() {
        byte[] grande = imagem(6_000, 4_000, BufferedImage.TYPE_BYTE_GRAY, "png"); // ~24 MP, descodifica-se em vários ms
        ValidacaoException e = assertThrows(ValidacaoException.class,
                () -> Imagens.miniatura(grande, 320, 220, Duration.ZERO));
        assertTrue(e.getMessage().contains("demorou"), e.getMessage());
        assertNotNull(Imagens.miniatura(png(10, 10), 320, 220, LIMITE)); // o fio não ficou preso
    }
}
