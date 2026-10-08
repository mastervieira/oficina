package pt.oficina.model;

public record Localizacao(Long id, String nome) {
    @Override
    public String toString() {
        return nome;
    }
}
