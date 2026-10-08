package pt.oficina.model;

import pt.oficina.model.enums.TipoCategoria;

/** {@code id} é null antes de o registo ser gravado. */
public record Categoria(Long id, String nome, TipoCategoria tipo) {
    /** Nas listas, as categorias de artigos com tipo específico mostram-no: "Pastilhas (Consumível)". */
    @Override
    public String toString() {
        return tipo == TipoCategoria.MAQUINA || tipo == TipoCategoria.ARTIGO ? nome : nome + " (" + tipo + ")";
    }
}
