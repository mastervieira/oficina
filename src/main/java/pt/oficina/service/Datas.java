package pt.oficina.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;

/** Validações e leitura de datas partilhadas pelos serviços e pelos formulários. */
public final class Datas {

    private static final DateTimeFormatter DMA = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    /**
     * Formatos aceites ao ler texto escrito por pessoas. STRICT: sem ele, "30/02/2026" passava a 28/02/2026 em silêncio
     * (o modo por omissão, SMART, "corrige" o dia para o último do mês).
     */
    private static final List<DateTimeFormatter> FORMATOS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT));

    private Datas() {
    }

    /** Os registos descrevem factos já ocorridos: não aceitam datas futuras. */
    static void naoFutura(LocalDate data, LocalDate hoje, String campo) {
        if (data.isAfter(hoje)) {
            throw new ValidacaoException(campo + " não pode ser futura.");
        }
    }

    /** "31/12/2026". */
    public static String formatar(LocalDate data) {
        return DMA.format(data);
    }

    /**
     * Lê "31/12/2026", "31-12-2026", "31.12.2026" ou "2026-12-31" (o ano com 4 algarismos).
     *
     * @return a data, ou null se o texto não for uma data que exista (ex.: 30/02/2026)
     */
    public static LocalDate ler(String texto) {
        if (texto == null) {
            return null;
        }
        String s = texto.trim();
        for (DateTimeFormatter f : FORMATOS) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException tentarOutro) {
                // próximo formato
            }
        }
        return null;
    }
}
