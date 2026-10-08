package pt.oficina.ui.comum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import pt.oficina.service.ValidacaoException;

class FormatosTest {

    @Test
    void leVirgulaDecimalEInteiros() {
        assertEquals(2.5, Formatos.lerNumero("2,5", "x"));
        assertEquals(12, Formatos.lerNumero(" 12 ", "x"));
        assertEquals(0.000125, Formatos.lerNumero("0,000125", "x"), 1e-12);
        assertEquals(0, Formatos.lerNumero("", "x"));
        assertEquals(0, Formatos.lerNumero(null, "x"));
        assertEquals(1_000_000_000d, Formatos.lerNumero("1000000000", "x"));
    }

    @Test
    void recusaOPontoPorSerAmbiguo() {
        // "1.000" em pt-PT é mil; lido como 1 mudaria o stock em silêncio.
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> Formatos.lerNumero("1.000", "A quantidade"));
        assertEquals("A quantidade: use a vírgula como separador decimal (ex.: 2,5).", e.getMessage());
        assertThrows(ValidacaoException.class, () -> Formatos.lerNumero("2.5", "x"));
    }

    @Test
    void recusaLixoNegativosNotacaoCientificaEValoresEnormes() {
        for (String mau : new String[] {"abc", "-1", "+1", "1e308", "NaN", "Infinity", "1,2,3", "1 000", ",5", "5,",
                "12345678901", "1,1234567"}) {
            assertThrows(ValidacaoException.class, () -> Formatos.lerNumero(mau, "x"), mau);
        }
        assertThrows(ValidacaoException.class, () -> Formatos.lerNumero("1000000001", "x"));
        assertThrows(ValidacaoException.class, () -> Formatos.lerNumero("9999999999", "x"));
    }

    @Test
    void textoMostradoVoltaALerSeIgual() {
        for (double v : new double[] {0, 1, 2.5, 1234.5678, 0.000125, 999_999_999}) {
            assertEquals(v, Formatos.lerNumero(Formatos.numero(v), "x"), 1e-9, String.valueOf(v));
        }
    }
}
