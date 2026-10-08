package pt.oficina.model.enums;

public enum TipoIntervencao {
    PREVENTIVA("Preventiva"),
    PROGRAMADA("Programada"),
    CORRETIVA("Corretiva");

    private final String etiqueta;

    TipoIntervencao(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    /** Só uma intervenção preventiva ou programada pode ficar ligada a um plano de manutenção. */
    public boolean cumprePlano() {
        return this == PREVENTIVA || this == PROGRAMADA;
    }

    @Override
    public String toString() {
        return etiqueta;
    }
}
