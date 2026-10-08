package pt.oficina.service;

import java.time.LocalDate;
import pt.oficina.model.PlanoComPrazo;

/** Textos de prazo/situação de um plano, partilhados pelo itinerário e pela lista de planos. */
public final class Prazos {

    private Prazos() {
    }

    public static String situacao(PlanoComPrazo p, LocalDate hoje) {
        if (p.semIntervencao()) {
            return "Vencida (sem intervenção)";
        }
        return p.vencido(hoje) ? "Vencida" : (p.diasAte(hoje) <= ManutencaoService.JANELA_DIAS ? "A vencer" : "Em dia");
    }

    public static String prazo(PlanoComPrazo p, LocalDate hoje) {
        if (p.semIntervencao()) {
            return "Sem intervenção";
        }
        long dias = p.diasAte(hoje);
        if (dias < 0) {
            return -dias + (dias == -1 ? " dia de atraso" : " dias de atraso");
        }
        if (dias == 0) {
            return "Hoje";
        }
        return "em " + dias + (dias == 1 ? " dia" : " dias");
    }
}
