package pt.oficina.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DatasTest {

    @Test
    void leOsFormatosAceites() {
        LocalDate d = LocalDate.of(2026, 12, 31);
        assertEquals(d, Datas.ler("31/12/2026"));
        assertEquals(d, Datas.ler(" 31-12-2026 "));
        assertEquals(d, Datas.ler("31.12.2026"));
        assertEquals(d, Datas.ler("2026-12-31"));
        assertEquals(LocalDate.of(2026, 3, 5), Datas.ler("5/3/2026"));
        assertEquals("05/03/2026", Datas.formatar(LocalDate.of(2026, 3, 5)));
    }

    @Test
    void recusaDatasQueNaoExistemEAnosAbreviados() {
        for (String mau : new String[] {"30/02/2026", "29/02/2025", "31/04/2026", "32/01/2026", "1/13/2026", "5/3/26",
            "2026-02-30", "ontem", "", "  "}) {
            assertNull(Datas.ler(mau), mau);
        }
        assertNull(Datas.ler(null));
        assertEquals(LocalDate.of(2024, 2, 29), Datas.ler("29/02/2024"));
    }
}
