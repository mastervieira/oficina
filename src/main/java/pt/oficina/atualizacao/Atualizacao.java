package pt.oficina.atualizacao;

import java.net.URI;

/**
 * Uma versão mais recente do que a instalada.
 *
 * @param versao a versão disponível, por exemplo "1.2.0"
 * @param pagina página (https) onde se descarrega
 * @param notas  o que mudou, em texto simples; pode ser vazio
 */
public record Atualizacao(String versao, URI pagina, String notas) {
}
