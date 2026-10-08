package pt.oficina.model;

/** Artigo com o stock atual, sempre calculado pela soma dos movimentos (nunca guardado). */
public record StockArtigo(Artigo artigo, double stock) {

    private static final double EPS = 1e-9;

    public boolean abaixoDoMinimo() {
        return stock < artigo.stockMinimo() - EPS;
    }
}
