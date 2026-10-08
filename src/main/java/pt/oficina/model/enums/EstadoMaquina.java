package pt.oficina.model.enums;

public enum EstadoMaquina {
    ATIVO("Ativo"),
    INOPERATIVO("Inoperativo"),
    MANUTENCAO("Manutenção"),
    ABATIDO("Abatido");

    private final String etiqueta;

    EstadoMaquina(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    /** Texto mostrado nas listas (ComboBox, tabelas). */
    @Override
    public String toString() {
        return etiqueta;
    }
}
