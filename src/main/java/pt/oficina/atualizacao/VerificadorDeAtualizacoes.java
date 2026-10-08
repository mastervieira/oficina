package pt.oficina.atualizacao;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import pt.oficina.Versao;

/**
 * Pergunta, ao arrancar, se há uma versão mais recente. Só <b>avisa</b>: nunca descarrega nem instala nada, por isso
 * um servidor comprometido não consegue mais do que mostrar um texto e uma ligação https que o utilizador escolhe abrir.
 *
 * <p>O endereço vem do pom ({@code -Datualizacoes.url=...} ao compilar a release) ou de
 * {@code -Doficina.atualizacoes=...}; {@code off} desliga a verificação. Sem endereço não faz nada. O ficheiro
 * remoto é um {@code .properties} de poucas linhas:
 * <pre>
 * versao=1.2.0
 * pagina=https://exemplo.pt/oficina/descargas
 * notas=Corrige a importação de Excel.
 * </pre>
 *
 * <p>Defesas: https obrigatório (http só para localhost, nos testes), redirecionamentos nunca para http, tamanho
 * máximo da resposta, tempo total limitado, versão e ligação validadas, e qualquer falha (sem rede, resposta
 * estranha) é silenciosa.
 */
public final class VerificadorDeAtualizacoes {

    public static final String PROP = "oficina.atualizacoes";
    public static final Duration LIMITE = Duration.ofSeconds(8);

    static final int MAX_BYTES = 16 * 1024;
    private static final int MAX_NOTAS = 500;
    private static final int MAX_URL = 2048;

    private VerificadorDeAtualizacoes() {
    }

    /** O endereço a consultar, ou vazio se a verificação está desligada ou não configurada. */
    public static Optional<URI> enderecoConfigurado() {
        String indicado = System.getProperty(PROP);
        String texto = indicado != null ? indicado : Versao.urlAtualizacoes();
        if (texto == null || texto.isBlank() || igualAscii(texto.trim(), "off")) {
            return Optional.empty();
        }
        return enderecoAceite(texto.trim());
    }

    /**
     * Consulta num fio de fundo (daemon) e chama {@code aoEncontrar} nesse fio, só se houver versão mais recente.
     * Quem chama passa para o fio da interface.
     */
    public static void verificarEmSegundoPlano(Consumer<Atualizacao> aoEncontrar) {
        Optional<URI> endereco = enderecoConfigurado();
        if (endereco.isEmpty()) {
            return;
        }
        Thread fio = new Thread(() -> {
            try {
                consultar(endereco.get(), Versao.atual(), LIMITE).ifPresent(aoEncontrar);
            } catch (RuntimeException e) {
                // verificar é um extra: nunca incomoda o utilizador nem escreve o endereço em lado nenhum
            }
        }, "oficina-atualizacoes");
        fio.setDaemon(true);
        fio.start();
    }

    /**
     * @return a atualização, ou vazio se não há versão mais recente, a resposta não é válida, ou falhou a ligação
     */
    static Optional<Atualizacao> consultar(URI endereco, String versaoAtual, Duration limite) {
        if (!Versoes.valida(versaoAtual) || enderecoAceite(endereco.toString()).isEmpty()) {
            return Optional.empty();
        }
        HttpClient cliente = HttpClient.newBuilder()
                .connectTimeout(limite)
                .followRedirects(HttpClient.Redirect.NORMAL) // nunca de https para http
                .build();
        HttpRequest pedido = HttpRequest.newBuilder(endereco)
                .timeout(limite)
                .header("User-Agent", "Oficina/" + versaoAtual)
                .GET()
                .build();
        try {
            // limiting(): lê no máximo MAX_BYTES + 1 do corpo; orTimeout(): limita o tempo TOTAL, corpo incluído
            HttpResponse<byte[]> resposta = cliente.sendAsync(pedido,
                            HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BYTES + 1))
                    .orTimeout(limite.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .join();
            if (resposta.statusCode() != 200 || resposta.body().length > MAX_BYTES) {
                return Optional.empty();
            }
            return interpretar(resposta.body(), versaoAtual);
        } catch (CompletionException | IllegalArgumentException | SecurityException e) {
            return Optional.empty();
        } finally {
            cliente.close();
        }
    }

    static Optional<Atualizacao> interpretar(byte[] corpo, String versaoAtual) {
        String texto;
        try {
            texto = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(corpo)).toString();
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try {
            p.load(new StringReader(texto));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
        String versao = p.getProperty("versao", "").trim();
        if (!Versoes.valida(versao) || Versoes.comparar(versao, versaoAtual) <= 0) {
            return Optional.empty();
        }
        Optional<URI> pagina = enderecoHttps(p.getProperty("pagina", "").trim());
        if (pagina.isEmpty()) {
            return Optional.empty();
        }
        String notas = p.getProperty("notas", "").replaceAll("\\p{Cntrl}", " ").trim();
        if (notas.length() > MAX_NOTAS) {
            notas = notas.substring(0, MAX_NOTAS) + "…";
        }
        return Optional.of(new Atualizacao(versao, pagina.get(), notas));
    }

    /** O endereço a consultar: https, ou http só para localhost (testes). */
    private static Optional<URI> enderecoAceite(String texto) {
        Optional<URI> https = enderecoHttps(texto);
        if (https.isPresent()) {
            return https;
        }
        try {
            URI u = URI.create(texto);
            boolean local = igualAscii(u.getScheme(), "http") && u.getUserInfo() == null
                    && (igualAscii(u.getHost(), "localhost") || "127.0.0.1".equals(u.getHost()));
            return local && texto.length() <= MAX_URL ? Optional.of(u) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Igualdade ignorando maiúsculas só do alfabeto ASCII. Não se usa equalsIgnoreCase: com regras Unicode,
     * "httpſ" (com o S longo) seria igual a "https".
     */
    static boolean igualAscii(String texto, String esperadoEmMinusculas) {
        if (texto == null || texto.length() != esperadoEmMinusculas.length()) {
            return false;
        }
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c += 'a' - 'A';
            }
            if (c != esperadoEmMinusculas.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Uma ligação https válida, sem utilizador:palavra-passe, e de tamanho razoável. */
    private static Optional<URI> enderecoHttps(String texto) {
        if (texto.isEmpty() || texto.length() > MAX_URL) {
            return Optional.empty();
        }
        try {
            URI u = URI.create(texto);
            boolean ok = igualAscii(u.getScheme(), "https") && u.getHost() != null && !u.getHost().isBlank()
                    && u.getUserInfo() == null;
            return ok ? Optional.of(u) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
