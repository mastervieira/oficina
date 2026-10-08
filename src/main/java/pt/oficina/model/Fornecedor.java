package pt.oficina.model;

public record Fornecedor(Long id, String nome, String contacto) {
    @Override
    public String toString() {
        return nome;
    }
}
