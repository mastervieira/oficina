package pt.oficina.atualizacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A consulta de versões fala com um servidor que não controlamos: tudo o que vem de lá é não fiável. */
class VerificadorDeAtualizacoesTest {

    private static final Duration LIMITE = Duration.ofSeconds(3);
    private static final String PAGINA = "https://exemplo.pt/oficina/descargas";

    private HttpServer servidor;
    private final AtomicReference<String> agente = new AtomicReference<>();
    private volatile int estado = 200;
    private volatile byte[] corpo = new byte[0];
    private volatile long atrasoMs;

    @BeforeEach
    void arrancar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        servidor.createContext("/latest.properties", troca -> {
            agente.set(troca.getRequestHeaders().getFirst("User-Agent"));
            try {
                if (atrasoMs > 0) {
                    Thread.sleep(atrasoMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            troca.sendResponseHeaders(estado, corpo.length == 0 ? -1 : corpo.length);
            if (corpo.length > 0) {
                troca.getResponseBody().write(corpo);
            }
            troca.close();
        });
        servidor.createContext("/redireciona", troca -> {
            troca.getResponseHeaders().add("Location", "http://example.invalid/latest.properties");
            troca.sendResponseHeaders(302, -1);
            troca.close();
        });
        servidor.start();
    }

    @AfterEach
    void parar() {
        servidor.stop(0);
    }

    private URI endereco() {
        return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/latest.properties");
    }

    private void responder(String texto) {
        corpo = texto.getBytes(StandardCharsets.UTF_8);
    }

    private Optional<Atualizacao> consultar(String versaoAtual) {
        return VerificadorDeAtualizacoes.consultar(endereco(), versaoAtual, LIMITE);
    }

    @Test
    void avisaQuandoHaVersaoMaisRecente() {
        responder("versao=1.2.0\npagina=" + PAGINA + "\nnotas=Corrige a importação de Excel.\n");
        Atualizacao a = consultar("1.1.0").orElseThrow();
        assertEquals("1.2.0", a.versao());
        assertEquals(URI.create(PAGINA), a.pagina());
        assertEquals("Corrige a importação de Excel.", a.notas());
        assertEquals("Oficina/1.1.0", agente.get(), "só a versão vai no pedido");
    }

    @Test
    void naoAvisaSeAVersaoIgualOuMaisRecente() {
        responder("versao=1.2.0\npagina=" + PAGINA + "\n");
        assertTrue(consultar("1.2.0").isEmpty());
        assertTrue(consultar("1.3.0").isEmpty());
        assertTrue(consultar("1.2.1-SNAPSHOT").isEmpty());
        assertTrue(consultar("1.2.0-SNAPSHOT").isPresent(), "a final é mais recente que a SNAPSHOT do mesmo número");
    }

    @Test
    void respostasInvalidasSaoIgnoradas() {
        for (String r : new String[] {
                "", "isto não é um ficheiro de versões", "versao=1.2.0\n", // sem página
                "versao=abc\npagina=" + PAGINA, "versao=1.2\npagina=" + PAGINA,
                "versao=1.2.0\npagina=http://exemplo.pt/x", // página sem https
                "versao=1.2.0\npagina=file:///etc/passwd",
                "versao=1.2.0\npagina=javascript:alert(1)",
                "versao=1.2.0\npagina=https://utilizador:segredo@exemplo.pt/x",
                "versao=1.2.0\npagina=https:///sem-anfitriao",
                "versao=1.2.0\npagina=httpſ://exemplo.pt/x", // "ſ" em maiúsculas é S: não pode passar por https
                "versao=1.2.0\npagina=https://exemplo.pt/ espaço"}) {
            responder(r);
            assertTrue(consultar("1.0.0").isEmpty(), r);
        }
        corpo = new byte[] {'v', 'e', 'r', 's', 'a', 'o', '=', (byte) 0xC3, 0x28}; // UTF-8 inválido
        assertTrue(consultar("1.0.0").isEmpty());
    }

    @Test
    void erroDoServidorOuRespostaGrandeDemaisSaoIgnorados() {
        responder("versao=1.2.0\npagina=" + PAGINA + "\n");
        estado = 500;
        assertTrue(consultar("1.0.0").isEmpty());
        estado = 404;
        assertTrue(consultar("1.0.0").isEmpty());

        estado = 200;
        responder("versao=1.2.0\npagina=" + PAGINA + "\nnotas=" + "x".repeat(VerificadorDeAtualizacoes.MAX_BYTES));
        assertTrue(consultar("1.0.0").isEmpty(), "resposta acima do limite de tamanho");
    }

    @Test
    void notasLongasECaracteresDeControloSaoLimpos() {
        responder("versao=1.2.0\npagina=" + PAGINA + "\nnotas=" + "a\\tb".repeat(400));
        String notas = consultar("1.0.0").orElseThrow().notas();
        assertTrue(notas.length() <= 501, "tamanho " + notas.length());
        assertFalse(notas.contains("\t"));
    }

    @Test
    void servidorLentoNaoPrendeAAplicacao() {
        responder("versao=1.2.0\npagina=" + PAGINA + "\n");
        atrasoMs = 4_000;
        long inicio = System.nanoTime();
        assertTrue(VerificadorDeAtualizacoes.consultar(endereco(), "1.0.0", Duration.ofMillis(500)).isEmpty());
        assertTrue(Duration.ofNanos(System.nanoTime() - inicio).toMillis() < 3_000, "demorou mais que o tempo limite");
    }

    @Test
    void semServidorFalhaEmSilencio() {
        servidor.stop(0);
        assertTrue(consultar("1.0.0").isEmpty());
    }

    @Test
    void naoSegueRedirecionamentoParaHttp() {
        URI redireciona = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/redireciona");
        assertTrue(VerificadorDeAtualizacoes.consultar(redireciona, "1.0.0", LIMITE).isEmpty());
    }

    @Test
    void soAceitaHttpsOuLocalhost() {
        responder("versao=1.2.0\npagina=" + PAGINA + "\n");
        for (String u : new String[] {"http://exemplo.pt/latest.properties", "ftp://exemplo.pt/x", "file:///tmp/x",
                "http://user@127.0.0.1/x"}) {
            assertTrue(VerificadorDeAtualizacoes.consultar(URI.create(u), "1.0.0", LIMITE).isEmpty(), u);
        }
    }

    @Test
    void igualdadeAsciiNaoSeEnganaComUnicode() {
        assertTrue(VerificadorDeAtualizacoes.igualAscii("HTTPS", "https"));
        assertFalse(VerificadorDeAtualizacoes.igualAscii("httpſ", "https"));
        assertFalse(VerificadorDeAtualizacoes.igualAscii("İ", "i"));
        assertFalse(VerificadorDeAtualizacoes.igualAscii(null, "https"));
    }

    @Test
    void propriedadeDesligaOuSubstituiOEndereco() {
        String anterior = System.getProperty(VerificadorDeAtualizacoes.PROP);
        try {
            System.setProperty(VerificadorDeAtualizacoes.PROP, "off");
            assertTrue(VerificadorDeAtualizacoes.enderecoConfigurado().isEmpty());
            System.setProperty(VerificadorDeAtualizacoes.PROP, "http://exemplo.pt/x"); // http não é aceite
            assertTrue(VerificadorDeAtualizacoes.enderecoConfigurado().isEmpty());
            System.setProperty(VerificadorDeAtualizacoes.PROP, "httpſ://exemplo.pt/x");
            assertTrue(VerificadorDeAtualizacoes.enderecoConfigurado().isEmpty());
            System.setProperty(VerificadorDeAtualizacoes.PROP, " OFF ");
            assertTrue(VerificadorDeAtualizacoes.enderecoConfigurado().isEmpty());
            System.setProperty(VerificadorDeAtualizacoes.PROP, "https://exemplo.pt/latest.properties");
            assertEquals(URI.create("https://exemplo.pt/latest.properties"),
                    VerificadorDeAtualizacoes.enderecoConfigurado().orElseThrow());
        } finally {
            if (anterior == null) {
                System.clearProperty(VerificadorDeAtualizacoes.PROP);
            } else {
                System.setProperty(VerificadorDeAtualizacoes.PROP, anterior);
            }
        }
    }
}
