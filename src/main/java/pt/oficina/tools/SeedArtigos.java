package pt.oficina.tools;

import java.util.ArrayList;
import java.util.List;
import pt.oficina.model.enums.TipoCategoria;

/**
 * Famílias de artigos de oficina (torno e fresa) para o seed. Cada descrição obtém-se combinando listas de
 * variantes (tipo, medida, material, ...) a partir do número de ordem, por isso são todas diferentes e
 * reprodutíveis. Se um pedido exceder as combinações possíveis, acrescenta-se "(variante N)".
 */
final class SeedArtigos {

    /** Peso: nº de artigos por cada 10 000 (a soma dos pesos é 10 000). */
    record Familia(String categoria, TipoCategoria tipo, String prefixo, int peso, String unidade,
            int minLo, int minHi, String formato, String[][] listas) {

        String descricao(int indice) {
            int resto = indice;
            Object[] valores = new Object[listas.length];
            for (int k = 0; k < listas.length; k++) {
                valores[k] = listas[k][resto % listas[k].length];
                resto /= listas[k].length;
            }
            String texto = String.format(formato, valores);
            return resto == 0 ? texto : texto + " (variante " + (resto + 1) + ")";
        }

        String codigo(int indice) {
            return String.format("%s-%05d", prefixo, indice + 1);
        }
    }

    static final List<Familia> FAMILIAS = criarFamilias();

    private SeedArtigos() {
    }

    /** Número de artigos de cada família para um total pedido (soma exatamente {@code total}). */
    static int[] contagens(int total) {
        int[] r = new int[FAMILIAS.size()];
        long acumulado = 0;
        long anterior = 0;
        for (int i = 0; i < r.length; i++) {
            acumulado += FAMILIAS.get(i).peso();
            long ate = Math.round(acumulado * (double) total / 10_000);
            r[i] = (int) (ate - anterior);
            anterior = ate;
        }
        return r;
    }

    private static List<Familia> criarFamilias() {
        List<Familia> f = new ArrayList<>();
        f.add(new Familia("Pastilhas de corte", TipoCategoria.FERRAMENTA, "PAS", 1500, "cx", 2, 20,
                "Pastilha %s %s %s", new String[][] {
                    v("CNMG 120404", "CNMG 120408", "CNMG 120412", "DNMG 150404", "DNMG 150408", "DNMG 150604",
                            "TNMG 160404", "TNMG 160408", "VNMG 160404", "VBMT 110302", "VBMT 160404", "WNMG 080404",
                            "WNMG 080408", "SNMG 120408", "CCMT 09T304", "CCMT 060204", "TCMT 110204", "DCMT 11T304",
                            "RCMT 1204", "APMT 1135"),
                    v("PM", "MF", "MR", "QM", "PF", "PR", "UR", "KF"),
                    v("GC4325", "GC4335", "GC2220", "GC1125", "GC3330", "TP2500", "TP3500", "CTC1135", "CTP1135",
                            "GC1010")}));
        f.add(new Familia("Brocas", TipoCategoria.FERRAMENTA, "BRO", 1200, "un", 2, 20,
                "Broca %s %s Ø%s mm, %s", new String[][] {
                    v("HSS", "HSS-Co", "Metal duro", "Metal duro c/ refrigeração interna", "de centrar HSS", "escalonada"),
                    v("curta", "série normal", "longa", "extra-longa"),
                    medidas(1.0, 20.0, 0.5),
                    v("sem revestimento", "TiN", "TiAlN")}));
        f.add(new Familia("Fresas", TipoCategoria.FERRAMENTA, "FRE", 1500, "un", 1, 10,
                "Fresa %s Ø%s mm Z%s %s", new String[][] {
                    v("topo reto", "esférica", "de facejar", "de ranhurar", "toroidal", "de chanfrar 45°", "de roscar",
                            "de desbaste"),
                    v("2", "3", "4", "5", "6", "8", "10", "12", "14", "16", "20", "25", "32", "40", "50", "63"),
                    v("2", "3", "4", "5", "6"),
                    v("HSS", "HSS-Co8", "Metal duro", "Metal duro TiAlN")}));
        f.add(new Familia("Machos e tarraxas", TipoCategoria.FERRAMENTA, "MAC", 600, "un", 2, 12,
                "%s M%s %s %s %s", new String[][] {
                    v("Macho de máquina", "Macho manual", "Macho de laminar", "Tarraxa"),
                    v("3", "4", "5", "6", "8", "10", "12", "14", "16", "18", "20", "24"),
                    v("passo normal", "passo fino"),
                    v("HSS", "HSS-Co", "HSS-TiN", "Metal duro", "Inox"),
                    v("6H", "6G")}));
        f.add(new Familia("Alargadores", TipoCategoria.FERRAMENTA, "ALA", 300, "un", 1, 5,
                "Alargador %s Ø%s mm H7 %s, haste %s", new String[][] {
                    v("de máquina", "manual", "cónico", "expansível"),
                    inteiros(4, 29),
                    v("HSS", "Metal duro"),
                    v("reta", "cónica")}));
        f.add(new Familia("Suportes e mandris", TipoCategoria.FERRAMENTA, "SUP", 700, "un", 0, 3,
                "Suporte %s %s %s %s", new String[][] {
                    v("SCLCR", "SDJCR", "MTJNR", "PCLNR", "SVJBR", "SDNCN", "SSSCR", "STGCR", "SRDCN", "MCLNR",
                            "DWLNR", "PSSNR", "SVVBN", "CTFPR"),
                    v("1616", "2020", "2525", "3225", "3232", "4040"),
                    v("95°", "93°", "107°", "91°"),
                    v("direito", "esquerdo")}));
        f.add(new Familia("Instrumentos de medição", TipoCategoria.FERRAMENTA, "MED", 300, "un", 0, 2,
                "%s %s %s", new String[][] {
                    v("Paquímetro", "Micrómetro exterior", "Micrómetro interior", "Comparador", "Relógio apalpador",
                            "Calibre tampão", "Calibre de roscas", "Régua graduada", "Gabarito de raios", "Esquadro"),
                    v("0-25 mm", "25-50 mm", "50-75 mm", "75-100 mm", "0-150 mm", "0-200 mm", "0-300 mm", "0-500 mm"),
                    v("digital", "analógico", "certificado")}));
        f.add(new Familia("Óleos e refrigerantes", TipoCategoria.CONSUMIVEL, "OLE", 250, "un", 2, 20,
                "%s %s, embalagem %s", new String[][] {
                    v("Óleo de corte integral", "Óleo solúvel", "Óleo hidráulico", "Óleo de guias",
                            "Massa lubrificante", "Refrigerante sintético", "Anticorrosivo"),
                    v("standard", "premium", "bio", "alta pressão"),
                    v("1 L", "5 L", "20 L", "60 L", "200 L")}));
        f.add(new Familia("Abrasivos", TipoCategoria.CONSUMIVEL, "ABR", 700, "un", 10, 100,
                "Disco %s Ø%s mm grão %s (%s)", new String[][] {
                    v("de corte", "de desbaste", "de lamelas", "flap", "de desbaste inox"),
                    v("115", "125", "150", "180", "230", "300"),
                    v("24", "36", "40", "60", "80", "100", "120", "180", "240"),
                    v("aço", "inox", "alumínio")}));
        f.add(new Familia("Parafusaria", TipoCategoria.CONSUMIVEL, "PAR", 1500, "cx", 5, 50,
                "Parafuso %s M%s x%s mm %s", new String[][] {
                    v("DIN 912 cab. cilíndrica", "DIN 933 cab. sextavada", "DIN 7991 cab. cónica",
                            "DIN 965 cab. tronco-cónica", "ISO 4762 cab. cilíndrica"),
                    v("3", "4", "5", "6", "8", "10", "12", "14", "16", "20"),
                    v("8", "10", "12", "16", "20", "25", "30", "35", "40", "50", "60", "70", "80"),
                    v("A2 inox", "8.8 zincado", "10.9", "12.9 preto")}));
        f.add(new Familia("Rolamentos e correias", TipoCategoria.CONSUMIVEL, "ROL", 500, "un", 2, 10,
                "%s %s ref. %s", new String[][] {
                    v("Rolamento rígido de esferas", "Rolamento de rolos cónicos", "Rolamento axial",
                            "Rolamento de agulhas", "Correia trapezoidal", "Correia dentada"),
                    v("standard", "reforçado", "vedado 2RS", "aberto"),
                    inteiros(10, 34)}));
        f.add(new Familia("Filtros", TipoCategoria.CONSUMIVEL, "FIL", 200, "un", 2, 10,
                "Filtro %s %s ref. F%s", new String[][] {
                    v("de óleo", "de ar", "hidráulico", "de refrigerante", "de ar comprimido"),
                    v("standard", "alta eficiência", "inox"),
                    inteiros(100, 119)}));
        f.add(new Familia("EPI", TipoCategoria.OUTROS, "EPI", 300, "un", 5, 50,
                "%s modelo %s tam. %s", new String[][] {
                    v("Luvas de proteção", "Óculos de proteção", "Protetores auriculares", "Máscara FFP2",
                            "Calçado de segurança", "Bata", "Viseira", "Capacete"),
                    v("A", "B", "C", "D", "E", "F"),
                    v("S", "M", "L", "XL", "38", "40", "42", "44")}));
        f.add(new Familia("Limpeza e embalagem", TipoCategoria.OUTROS, "LMP", 250, "un", 5, 40,
                "%s %s %s", new String[][] {
                    v("Pano de limpeza", "Papel industrial", "Desengordurante", "Spray de limpeza", "Fita adesiva",
                            "Filme extensível", "Caixa de cartão", "Saco de plástico", "Etiquetas", "Cola"),
                    v("pack 1", "pack 10", "pack 50", "grande", "pequeno", "industrial"),
                    v("tipo A", "tipo B", "tipo C", "tipo D")}));
        f.add(new Familia("Peças de reposição", TipoCategoria.ARTIGO, "REP", 200, "un", 0, 4,
                "%s %s, modelo %s", new String[][] {
                    v("Correia", "Fusível", "Contator", "Relé", "Sensor de proximidade",
                            "Interruptor de fim de curso", "Motor passo-a-passo", "Eixo", "Veio", "Fuso de esferas",
                            "Bomba de refrigerante", "Mola", "Junta", "Retentor", "Roda dentada"),
                    v("para torno", "para fresadora", "universal", "para retificadora"),
                    v("1", "2", "3", "4")}));
        return List.copyOf(f);
    }

    private static String[] v(String... valores) {
        return valores;
    }

    /** "1,0", "1,5", ... com vírgula decimal. */
    private static String[] medidas(double de, double ate, double passo) {
        List<String> r = new ArrayList<>();
        for (double x = de; x <= ate + 1e-9; x += passo) {
            r.add(String.format("%.1f", x).replace('.', ','));
        }
        return r.toArray(String[]::new);
    }

    private static String[] inteiros(int de, int ate) {
        String[] r = new String[ate - de + 1];
        for (int i = 0; i < r.length; i++) {
            r[i] = String.valueOf(de + i);
        }
        return r;
    }
}
