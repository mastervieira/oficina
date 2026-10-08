package pt.oficina.service;

/** Limites de valores numéricos aceites (quantidades, stock mínimo, custos). */
public final class Limites {

    /** Valor máximo aceite em quantidades, stock mínimo e custos. */
    public static final double MAX_VALOR = 1_000_000_000d;

    private Limites() {
    }

    /** Rejeita NaN, infinitos, negativos e valores acima de {@link #MAX_VALOR}. */
    static void validar(double valor, String campo) {
        if (Double.isNaN(valor) || Double.isInfinite(valor) || valor < 0) {
            throw new ValidacaoException(campo + " tem de ser um número igual ou superior a zero.");
        }
        if (valor > MAX_VALOR) {
            throw new ValidacaoException(campo + " é demasiado grande (máximo 1 000 000 000).");
        }
    }
}
