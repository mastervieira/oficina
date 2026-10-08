package pt.oficina;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * A versão da aplicação. A fonte única é a {@code <version>} do pom: o Maven escreve-a em {@code versao.properties}
 * (filtragem de recursos) e no manifesto do jar executável (Implementation-Version).
 */
public final class Versao {

    private static final String DESCONHECIDA = "desconhecida";

    private Versao() {
    }

    /** Por exemplo "1.2.0" ou "1.3.0-SNAPSHOT"; "desconhecida" se o recurso não existir (ex.: classes sem Maven). */
    public static String atual() {
        try (InputStream in = Versao.class.getResourceAsStream("versao.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("versao", "").trim();
                if (!v.isEmpty() && !v.startsWith("${")) { // "${...}": ficheiro não filtrado
                    return v;
                }
            }
        } catch (IOException e) {
            // cai para o manifesto
        }
        String manifesto = Versao.class.getPackage().getImplementationVersion();
        return manifesto == null || manifesto.isBlank() ? DESCONHECIDA : manifesto;
    }
}
