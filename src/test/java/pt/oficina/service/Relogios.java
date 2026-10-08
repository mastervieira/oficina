package pt.oficina.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Relógios fixos para os testes não dependerem da data real. */
final class Relogios {

    private Relogios() {
    }

    static Clock em(LocalDate dia) {
        ZoneId zona = ZoneId.systemDefault();
        return Clock.fixed(dia.atTime(12, 0).atZone(zona).toInstant(), zona);
    }
}
