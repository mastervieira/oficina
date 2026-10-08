package pt.oficina.model.enums;

public enum TipoMovimento {
    ENTRADA("Entrada"),
    SAIDA("Saída");

    private final String etiqueta;

    TipoMovimento(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    @Override
    public String toString() {
        return etiqueta;
    }
}
