package pt.oficina.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.oficina.db.Database;
import pt.oficina.model.Artigo;
import pt.oficina.model.StockArtigo;
import pt.oficina.model.enums.TipoCategoria;
import pt.oficina.model.enums.TipoMovimento;

class StockServiceTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 4);

    private Database db;
    private StockService stock;
    private Artigo pastilha;
    private Artigo broca;

    @BeforeEach
    void preparar() throws SQLException {
        db = Database.inMemory();
        InventarioService inv = new InventarioService(db.connection(), Relogios.em(HOJE));
        stock = new StockService(db.connection(), Relogios.em(HOJE));
        long cat = inv.criarCategoria("Consumíveis", TipoCategoria.ARTIGO).id();
        pastilha = inv.guardarArtigo(new Artigo(null, "P01", "Pastilha", cat, null, null, "un", 5));
        broca = inv.guardarArtigo(new Artigo(null, "B01", "Broca", cat, null, null, "un", 0));
    }

    @AfterEach
    void fechar() throws SQLException {
        db.close();
    }

    @Test
    void stockESempreASomaDosMovimentos() throws SQLException {
        assertEquals(0, stock.stockDe(pastilha.id()));
        stock.registar(pastilha.id(), TipoMovimento.ENTRADA, HOJE, 10, null);
        stock.registar(pastilha.id(), TipoMovimento.SAIDA, HOJE, 3.5, "Peça X");
        stock.registar(pastilha.id(), TipoMovimento.ENTRADA, HOJE, 1, null);
        assertEquals(7.5, stock.stockDe(pastilha.id()), 1e-9);

        List<StockArtigo> todos = stock.stockAtual();
        assertEquals(2, todos.size());
        StockArtigo p = todos.stream().filter(s -> s.artigo().id().equals(pastilha.id())).findFirst().orElseThrow();
        StockArtigo b = todos.stream().filter(s -> s.artigo().id().equals(broca.id())).findFirst().orElseThrow();
        assertEquals(7.5, p.stock(), 1e-9);
        assertEquals(0, b.stock()); // artigo sem movimentos
    }

    @Test
    void saidaNaoPodeExcederOStock() throws SQLException {
        stock.registar(pastilha.id(), TipoMovimento.ENTRADA, HOJE, 4, null);
        assertThrows(ValidacaoException.class,
                () -> stock.registar(pastilha.id(), TipoMovimento.SAIDA, HOJE, 4.5, null));
        assertEquals(4, stock.stockDe(pastilha.id()));
        stock.registar(pastilha.id(), TipoMovimento.SAIDA, HOJE, 4, null); // saída exata é permitida
        assertEquals(0, stock.stockDe(pastilha.id()), 1e-9);
        assertEquals(2, stock.movimentos(pastilha.id()).size());
    }

    @Test
    void validacoes() {
        assertThrows(ValidacaoException.class, () -> stock.registar(pastilha.id(), TipoMovimento.ENTRADA, HOJE, 0, null));
        assertThrows(ValidacaoException.class, () -> stock.registar(pastilha.id(), TipoMovimento.ENTRADA, HOJE, -2, null));
        assertThrows(ValidacaoException.class,
                () -> stock.registar(pastilha.id(), TipoMovimento.ENTRADA, HOJE, Double.NaN, null));
        assertThrows(ValidacaoException.class, () -> stock.registar(pastilha.id(), TipoMovimento.ENTRADA, null, 1, null));
        assertThrows(ValidacaoException.class, () -> stock.registar(pastilha.id(), null, HOJE, 1, null));
        assertThrows(ValidacaoException.class, () -> stock.registar(999, TipoMovimento.ENTRADA, HOJE, 1, null));
    }

    @Test
    void dataFuturaELimitesDeQuantidadeRejeitados() throws SQLException {
        ValidacaoException futura = assertThrows(ValidacaoException.class,
                () -> stock.registar(pastilha.id(), TipoMovimento.ENTRADA, HOJE.plusDays(1), 1, null));
        assertEquals("A data não pode ser futura.", futura.getMessage());
        assertThrows(ValidacaoException.class,
                () -> stock.registar(pastilha.id(), TipoMovimento.ENTRADA, HOJE, Limites.MAX_VALOR + 1, null));
        assertThrows(ValidacaoException.class,
                () -> stock.registar(pastilha.id(), TipoMovimento.ENTRADA, HOJE, 1e308, null));
        stock.registar(pastilha.id(), TipoMovimento.ENTRADA, HOJE, Limites.MAX_VALOR, null); // o máximo é aceite
        assertEquals(Limites.MAX_VALOR, stock.stockDe(pastilha.id()));
        assertEquals(1, stock.movimentos(pastilha.id()).size());
    }

    @Test
    void alertaQuandoAbaixoDoMinimo() throws SQLException {
        // pastilha: mínimo 5; broca: mínimo 0
        assertEquals(List.of(pastilha.id()), stock.alertas().stream().map(s -> s.artigo().id()).toList());

        stock.registar(pastilha.id(), TipoMovimento.ENTRADA, HOJE, 5, null);
        assertTrue(stock.alertas().isEmpty(), "stock igual ao mínimo não é alerta");

        stock.registar(pastilha.id(), TipoMovimento.SAIDA, HOJE, 1, null);
        assertEquals(1, stock.alertas().size());
        assertFalse(stock.alertas().get(0).stock() >= 5);
    }

    @Test
    void movimentosOrdenadosDoMaisRecenteENotaVaziaGuardaNull() throws SQLException {
        stock.registar(pastilha.id(), TipoMovimento.ENTRADA, LocalDate.of(2026, 1, 1), 5, "  ");
        stock.registar(pastilha.id(), TipoMovimento.ENTRADA, LocalDate.of(2026, 3, 1), 5, " Compra ");
        stock.registar(broca.id(), TipoMovimento.ENTRADA, LocalDate.of(2026, 2, 1), 1, null);

        var doArtigo = stock.movimentos(pastilha.id());
        assertEquals(LocalDate.of(2026, 3, 1), doArtigo.get(0).dataMov());
        assertEquals("Compra", doArtigo.get(0).nota());
        assertNull(doArtigo.get(1).nota());
        assertEquals(3, stock.movimentos(null).size());
    }

    @Test
    void mensagemDeStockInsuficienteUsaAVirgulaDecimal() throws SQLException {
        stock.registar(broca.id(), TipoMovimento.ENTRADA, HOJE, 2.5, null);
        ValidacaoException e = assertThrows(ValidacaoException.class,
                () -> stock.registar(broca.id(), TipoMovimento.SAIDA, HOJE, 3, null));
        assertTrue(e.getMessage().contains("disponível 2,5 un"), e.getMessage());
        stock.registar(pastilha.id(), TipoMovimento.ENTRADA, HOJE, 0.0001, null);
        e = assertThrows(ValidacaoException.class, () -> stock.registar(pastilha.id(), TipoMovimento.SAIDA, HOJE, 1, null));
        assertTrue(e.getMessage().contains("disponível 0,0001 un"), e.getMessage()); // e não "1.0E-4"
    }
}
