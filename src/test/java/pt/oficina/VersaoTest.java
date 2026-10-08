package pt.oficina;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersaoTest {

    @Test
    void aVersaoVemDoPomEFoiFiltrada() {
        String v = Versao.atual();
        assertFalse(v.startsWith("${"), "versao.properties não foi filtrado pelo Maven: " + v);
        assertTrue(v.matches("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?"), "não é uma versão SemVer: " + v);
    }
}
