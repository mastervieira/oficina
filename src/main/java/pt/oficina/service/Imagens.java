package pt.oficina.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Leitura defensiva de imagens escolhidas pelo utilizador (ficheiros não fiáveis).
 *
 * <p>Ordem das defesas: tamanho em bytes (lido com limite, a partir de um só handle) → assinatura (magic bytes) contra
 * uma lista fechada → só o cabeçalho, com o leitor do JDK que corresponde a essa assinatura (dimensões e número de
 * frames) → só então se descodifica, num fio próprio, com sub-amostragem (nunca a resolução total), tempo limite e
 * cancelamento. A extensão do ficheiro nunca é consultada. SVG, WebP, TIFF, etc. são recusados.
 */
public final class Imagens {

    /** Tamanho máximo do ficheiro. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    /**
     * Dimensões máximas. O limite em bytes não chega: um PNG de poucos KB pode ter 30 000 x 30 000 píxeis e ocupar
     * gigabytes de memória ao ser descodificado.
     */
    public static final int MAX_LADO = 10_000;
    public static final long MAX_PIXEIS = 50_000_000L;
    /** Frames máximos de um GIF (só se mostra o primeiro, mas um GIF com milhares de frames é abuso). */
    public static final int MAX_FRAMES = 200;
    public static final Duration LIMITE_DESCODIFICAR = Duration.ofSeconds(10);

    private static final String NAO_E_IMAGEM = "O ficheiro não é uma imagem válida (PNG, JPEG, GIF ou BMP).";

    static {
        ImageIO.setUseCache(false); // nunca criar ficheiros temporários a descodificar
    }

    /**
     * Um só fio: a descodificação é CPU-bound, por isso threads virtuais não ajudam (ocupariam um fio-portador
     * sem preempção) e vários fios só multiplicariam o pico de memória. Daemon: não impede a aplicação de fechar.
     */
    private static final ExecutorService DESCODIFICADOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "oficina-imagem");
        t.setDaemon(true);
        return t;
    });

    private Imagens() {
    }

    private enum Formato {
        PNG("png"), JPEG("jpeg"), GIF("gif"), BMP("bmp");

        final String nome;

        Formato(String nome) {
            this.nome = nome;
        }
    }

    private record Cabecalho(int largura, int altura) {
    }

    // ---- ficheiro --------------------------------------------------------

    /**
     * Lê o ficheiro escolhido para memória, no máximo {@link #MAX_BYTES}. Só aceita ficheiros normais (um FIFO ou
     * dispositivo faria a leitura bloquear para sempre) e lê com limite do mesmo handle que abriu: o tamanho que
     * {@code stat} deu pode já não ser verdade. A partir daqui trabalha-se só sobre estes bytes (sem reabrir o ficheiro).
     * Os links simbólicos seguem-se: o utilizador escolheu-os; o que interessa é o que está no destino.
     *
     * @throws ValidacaoException com mensagens sem caminhos
     */
    public static byte[] lerFicheiro(Path ficheiro) {
        try {
            BasicFileAttributes atributos = Files.readAttributes(ficheiro, BasicFileAttributes.class);
            if (!atributos.isRegularFile()) {
                throw new ValidacaoException("O ficheiro escolhido não é um ficheiro normal.");
            }
            if (atributos.size() > MAX_BYTES) {
                throw demasiadoGrande();
            }
            try (InputStream in = Files.newInputStream(ficheiro)) {
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw demasiadoGrande();
                }
                return bytes;
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ValidacaoException v) {
                throw v;
            }
            throw new ValidacaoException("Não foi possível ler o ficheiro.");
        }
    }

    // ---- validação (só o cabeçalho) --------------------------------------

    /**
     * Recusa o que não é PNG, JPEG, GIF ou BMP, ou é grande demais (bytes, píxeis, frames). Só lê o cabeçalho: as
     * dimensões conferem-se antes de a imagem ser descodificada.
     */
    public static void validar(byte[] imagem) {
        lerCabecalho(imagem);
    }

    private static Cabecalho lerCabecalho(byte[] imagem) {
        if (imagem == null || imagem.length == 0) {
            throw new ValidacaoException("A imagem está vazia.");
        }
        if (imagem.length > MAX_BYTES) {
            throw demasiadoGrande();
        }
        Formato formato = detetar(imagem);
        if (formato == null) {
            throw new ValidacaoException(NAO_E_IMAGEM);
        }
        int largura;
        int altura;
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(imagem))) {
            ImageReader leitor = leitorDe(in, formato);
            try {
                leitor.setInput(in, false, true);
                largura = leitor.getWidth(0);
                altura = leitor.getHeight(0);
                verificarDimensoes(largura, altura);
                if (formato == Formato.GIF && leitor.getNumImages(true) > MAX_FRAMES) {
                    throw new ValidacaoException("A imagem tem frames a mais (máximo " + MAX_FRAMES + ").");
                }
            } finally {
                leitor.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ValidacaoException v) {
                throw v;
            }
            throw new ValidacaoException(NAO_E_IMAGEM);
        }
        return new Cabecalho(largura, altura);
    }

    private static void verificarDimensoes(int largura, int altura) {
        if (largura <= 0 || altura <= 0) {
            throw new ValidacaoException(NAO_E_IMAGEM);
        }
        if (largura > MAX_LADO || altura > MAX_LADO || (long) largura * altura > MAX_PIXEIS) {
            throw new ValidacaoException("A imagem tem " + largura + " x " + altura + " píxeis; o máximo é "
                    + MAX_LADO + " de lado e " + MAX_PIXEIS / 1_000_000 + " megapíxeis. Reduza-a e tente de novo.");
        }
    }

    /** O formato pelos primeiros bytes do ficheiro (a extensão não conta), ou null se não está na lista. */
    private static Formato detetar(byte[] b) {
        if (comecaPor(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Formato.PNG;
        }
        if (comecaPor(b, 0xFF, 0xD8, 0xFF)) {
            return Formato.JPEG;
        }
        if (comecaPor(b, 'G', 'I', 'F', '8', '7', 'a') || comecaPor(b, 'G', 'I', 'F', '8', '9', 'a')) {
            return Formato.GIF;
        }
        if (comecaPor(b, 'B', 'M')) {
            return Formato.BMP;
        }
        return null;
    }

    private static boolean comecaPor(byte[] b, int... assinatura) {
        if (b.length < assinatura.length) {
            return false;
        }
        for (int i = 0; i < assinatura.length; i++) {
            if ((b[i] & 0xFF) != assinatura[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * O primeiro leitor que lê este fluxo, é do formato já validado pela assinatura e é o do próprio JDK. Não "qualquer
     * leitor registado": uma biblioteca de terceiros na classpath não passa a fazer parte da superfície de ataque.
     */
    private static ImageReader leitorDe(ImageInputStream in, Formato formato) throws IOException {
        Iterator<ImageReader> leitores = ImageIO.getImageReaders(in);
        ImageReader escolhido = null;
        while (leitores.hasNext()) {
            ImageReader candidato = leitores.next();
            if (escolhido == null && formato.nome.equals(candidato.getFormatName().toLowerCase(Locale.ROOT))
                    && candidato.getOriginatingProvider().getClass().getName().startsWith("com.sun.imageio.plugins.")) {
                escolhido = candidato;
            } else {
                candidato.dispose();
            }
        }
        if (escolhido == null) {
            throw new ValidacaoException(NAO_E_IMAGEM);
        }
        return escolhido;
    }

    // ---- descodificação --------------------------------------------------

    /**
     * Descodifica a imagem (só o primeiro frame) reduzida por sub-amostragem a um tamanho que ainda cobre
     * {@code larguraMax} x {@code alturaMax}: a memória gasta depende da miniatura, não da resolução original.
     * Bloqueia quem chama (nunca o chamar a partir do fio da interface sem {@code SegundoPlano}) até ao fim ou ao
     * tempo limite; no limite, aborta o leitor e cancela.
     *
     * @throws ValidacaoException com mensagens sem caminhos nem detalhes internos
     */
    public static BufferedImage miniatura(byte[] imagem, int larguraMax, int alturaMax, Duration limite) {
        Cabecalho cabecalho = lerCabecalho(imagem);
        Formato formato = detetar(imagem);
        AtomicReference<ImageReader> leitorEmUso = new AtomicReference<>();
        Future<BufferedImage> trabalho = DESCODIFICADOR.submit(
                () -> descodificar(imagem, formato, cabecalho, larguraMax, alturaMax, leitorEmUso));
        try {
            return trabalho.get(limite.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancelar(trabalho, leitorEmUso);
            throw new ValidacaoException("A imagem demorou demasiado a abrir.");
        } catch (InterruptedException e) {
            cancelar(trabalho, leitorEmUso);
            Thread.currentThread().interrupt();
            throw new ValidacaoException("A abertura da imagem foi cancelada.");
        } catch (ExecutionException e) {
            Throwable causa = e.getCause();
            if (causa instanceof ValidacaoException v) {
                throw v;
            }
            if (causa instanceof OutOfMemoryError) {
                throw new ValidacaoException("A imagem é demasiado grande para ser aberta.");
            }
            throw new ValidacaoException("Não foi possível abrir a imagem (ficheiro corrompido ou não suportado).");
        }
    }

    private static void cancelar(Future<?> trabalho, AtomicReference<ImageReader> leitorEmUso) {
        ImageReader leitor = leitorEmUso.get();
        if (leitor != null) {
            leitor.abort(); // os leitores do JDK verificam o pedido entre linhas
        }
        trabalho.cancel(true);
    }

    private static BufferedImage descodificar(byte[] imagem, Formato formato, Cabecalho cabecalho, int larguraMax,
            int alturaMax, AtomicReference<ImageReader> leitorEmUso) throws IOException {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(imagem))) {
            ImageReader leitor = leitorDe(in, formato);
            leitorEmUso.set(leitor);
            try {
                leitor.setInput(in, false, true);
                // maior passo inteiro que mantém a miniatura >= ao tamanho pedido nas duas dimensões
                int passo = Math.max(1, Math.min(cabecalho.largura() / larguraMax, cabecalho.altura() / alturaMax));
                ImageReadParam param = leitor.getDefaultReadParam();
                param.setSourceSubsampling(passo, passo, 0, 0);
                return leitor.read(0, param);
            } finally {
                leitorEmUso.set(null);
                leitor.dispose();
            }
        }
    }

    private static ValidacaoException demasiadoGrande() {
        return new ValidacaoException("A imagem é demasiado grande (máximo " + MAX_BYTES / (1024 * 1024) + " MB).");
    }
}
