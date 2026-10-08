package pt.oficina.ui.comum;

import java.time.LocalDate;
import javafx.scene.control.DatePicker;
import javafx.util.StringConverter;
import pt.oficina.service.Datas;
import pt.oficina.service.ValidacaoException;

/**
 * Campo de data com leitura estrita (dd/mm/aaaa).
 *
 * <p>O DatePicker do JavaFX tem duas armadilhas: (1) o conversor por omissão aceita "30/02/2026" e guarda 28/02;
 * (2) se o texto escrito à mão for inválido, ao perder o foco descarta-o em silêncio e fica com a data anterior. Aqui o
 * conversor nunca falha (devolve o valor atual, por isso o texto escrito fica à vista) e {@link #ler} lê o texto do
 * campo, que é o que o utilizador vê, recusando datas que não existem.
 */
public final class CampoData {

    private CampoData() {
    }

    public static DatePicker criar(LocalDate inicial) {
        DatePicker dp = new DatePicker(inicial);
        dp.setPromptText("dd/mm/aaaa");
        dp.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate d) {
                return d == null ? "" : Datas.formatar(d);
            }

            @Override
            public LocalDate fromString(String texto) {
                if (texto == null || texto.isBlank()) {
                    return null;
                }
                LocalDate d = Datas.ler(texto);
                return d != null ? d : dp.getValue(); // inválido: não muda o valor nem apaga o texto escrito
            }
        });
        return dp;
    }

    /**
     * A data escrita no campo (null se vazio).
     *
     * @throws ValidacaoException se o texto não for uma data que exista
     */
    public static LocalDate ler(DatePicker dp, String campo) {
        String texto = dp.getEditor().getText();
        if (texto == null || texto.isBlank()) {
            dp.setValue(null);
            return null;
        }
        LocalDate d = Datas.ler(texto);
        if (d == null) {
            throw new ValidacaoException(campo + " «" + texto.trim() + "» não é uma data válida (use dd/mm/aaaa, ex.: 31/12/2026).");
        }
        dp.setValue(d);
        return d;
    }
}
