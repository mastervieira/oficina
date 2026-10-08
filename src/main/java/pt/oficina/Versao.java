package pt.oficina;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * A versão da aplicação e a configuração de build. A fonte única é o pom: o Maven escreve {@code <version>} e a
 * propriedade {@code atualizacoes.url} em {@code versao.properties} (filtragem de recursos) e a versão também no
 * manifesto do jar executável (Implementation-Version).
 */
public final class Versao {

    private static final String DESCONHECIDA = "desconhecida";
    private static final Properties PROPRIEDADES = carregar();

    private Versao() {
    }

    private static Properties carregar() {
        Properties p = new Properties();
        try (InputStream in = Versao.class.getResourceAsStream("versao.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // fica vazio: cai para o manifesto
        }
        return p;
    }

    /** O valor, ou null se falta ou é uma expressão do Maven que não foi filtrada ("${...}"). */
    private static String valor(String chave) {
        String v = PROPRIEDADES.getProperty(chave, "").trim();
        return v.isEmpty() || v.startsWith("${") ? null : v;
    }

    /** Por exemplo "1.2.0" ou "1.3.0-SNAPSHOT"; "desconhecida" se o recurso não existir (ex.: classes sem Maven). */
    public static String atual() {
        String v = valor("versao");
        if (v != null) {
            return v;
        }
        String manifesto = Versao.class.getPackage().getImplementationVersion();
        return manifesto == null || manifesto.isBlank() ? DESCONHECIDA : manifesto;
    }

    /** Endereço do ficheiro de versões, definido ao compilar a release; null se não foi definido. */
    public static String urlAtualizacoes() {
        return valor("atualizacoes");
    }
}
