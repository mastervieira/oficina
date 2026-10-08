package pt.oficina.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pt.oficina.model.Categoria;
import pt.oficina.model.Fornecedor;
import pt.oficina.model.Localizacao;

/** Listas de apoio (categorias, localizações, fornecedores) e resolução de id para nome. */
public final class Catalogos {

    private final List<Categoria> categoriasMaquina;
    private final List<Categoria> categoriasArtigo;
    private final List<Localizacao> localizacoes;
    private final List<Fornecedor> fornecedores;
    private final Map<Long, String> categoriaPorId = new HashMap<>();
    private final Map<Long, String> localizacaoPorId = new HashMap<>();
    private final Map<Long, String> fornecedorPorId = new HashMap<>();

    public Catalogos(List<Categoria> categoriasMaquina, List<Categoria> categoriasArtigo,
            List<Localizacao> localizacoes, List<Fornecedor> fornecedores) {
        this.categoriasMaquina = categoriasMaquina;
        this.categoriasArtigo = categoriasArtigo;
        this.localizacoes = localizacoes;
        this.fornecedores = fornecedores;
        categoriasMaquina.forEach(c -> categoriaPorId.put(c.id(), c.nome()));
        categoriasArtigo.forEach(c -> categoriaPorId.put(c.id(), c.nome()));
        localizacoes.forEach(l -> localizacaoPorId.put(l.id(), l.nome()));
        fornecedores.forEach(f -> fornecedorPorId.put(f.id(), f.nome()));
    }

    public static Catalogos vazio() {
        return new Catalogos(List.of(), List.of(), List.of(), List.of());
    }

    public List<Categoria> categoriasMaquina() {
        return categoriasMaquina;
    }

    public List<Categoria> categoriasArtigo() {
        return categoriasArtigo;
    }

    public List<Localizacao> localizacoes() {
        return localizacoes;
    }

    public List<Fornecedor> fornecedores() {
        return fornecedores;
    }

    /** Nome da categoria, ou "" se o id for null/desconhecido. */
    public String categoria(Long id) {
        return categoriaPorId.getOrDefault(id, "");
    }

    public String localizacao(Long id) {
        return localizacaoPorId.getOrDefault(id, "");
    }

    public String fornecedor(Long id) {
        return fornecedorPorId.getOrDefault(id, "");
    }
}
