package pt.oficina.model;

/** Um plano do tipo de uma máquina, com a situação dessa máquina (última e próxima intervenção). */
public record PlanoDaMaquina(Maquina maquina, PlanoComPrazo prazo) {
}
