package pt.oficina.atualizacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersoesTest {

    @Test
    void comparaNumericamenteENaoComoTexto() {
        assertTrue(Versoes.comparar("1.10.0", "1.9.0") > 0); // como texto, "1.10" < "1.9"
        assertTrue(Versoes.comparar("2.0.0", "1.99.99") > 0);
        assertTrue(Versoes.comparar("1.0.1", "1.0.2") < 0);
        assertEquals(0, Versoes.comparar("1.2.3", "1.2.3"));
    }

    @Test
    void versaoComSufixoEAnteriorAFinal() {
        assertTrue(Versoes.comparar("1.2.0-SNAPSHOT", "1.2.0") < 0);
        assertTrue(Versoes.comparar("1.2.0", "1.2.0-rc1") > 0);
        assertTrue(Versoes.comparar("1.2.0-rc1", "1.2.0-rc2") < 0);
        assertTrue(Versoes.comparar("1.2.1-SNAPSHOT", "1.2.0") > 0);
    }

    @Test
    void recusaFormatosEstranhos() {
        for (String m : new String[] {"", "1", "1.2", "1.2.3.4", "v1.2.3", "1.2.x", "1.2.3-", "1.2.3-a b", "99999999.0.0", null}) {
            assertFalse(Versoes.valida(m), String.valueOf(m));
        }
        assertThrows(IllegalArgumentException.class, () -> Versoes.comparar("abc", "1.0.0"));
    }
}
