package pt.oficina.excel;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import pt.oficina.model.Artigo;
import pt.oficina.model.HistoricoEstado;
import pt.oficina.model.Intervencao;
import pt.oficina.model.Maquina;
import pt.oficina.model.MovimentoStock;
import pt.oficina.model.PlanoDaMaquina;
import pt.oficina.model.PlanoManutencao;
import pt.oficina.model.StockArtigo;
import pt.oficina.service.Catalogos;
import pt.oficina.service.Prazos;

/**
 * O que se exporta, módulo a módulo: as mesmas colunas e os mesmos nomes (categoria, localização, fornecedor por nome,
 * não por id) que se veem no ecrã. Os cabeçalhos de máquinas, artigos, movimentos, intervenções e estados são os que a
 * importação reconhece, por isso um ficheiro exportado pode ser editado e voltar a importar-se (movimentos e
 * intervenções acrescentam registos: reimportar sem editar duplica-os, e a importação avisa disso).
 */
public final class ExcelExportacao {

    public static final List<String> TITULOS_MAQUINAS = List.of("Código", "Descrição", "Nº série", "Categoria",
            "Localização", "Fornecedor", "Data de aquisição", "Estado");
    public static final List<String> TITULOS_ARTIGOS = List.of("Código", "Descrição", "Categoria", "Localização",
            "Fornecedor", "Unidade", "Stock mínimo", "Stock atual");

    public static final List<String> TITULOS_STOCK = List.of("Código", "Descrição", "Categoria", "Localização",
            "Fornecedor", "Unidade", "Stock atual", "Stock mínimo", "Alerta");
    public static final List<String> TITULOS_MOVIMENTOS = List.of("Data", "Código", "Descrição", "Tipo", "Quantidade",
            "Unidade", "Nota");
    public static final List<String> TITULOS_PLANOS = List.of("Situação", "Código", "Descrição", "Tipo", "Tarefa",
            "Periodicidade (meses)", "Última", "Próxima", "Prazo");
    public static final List<String> TITULOS_INTERVENCOES = List.of("Data", "Código", "Descrição da máquina", "Tipo",
            "Plano", "Descrição", "Custo");
    public static final List<String> TITULOS_ESTADOS = List.of("Código", "Descrição", "Categoria", "Localização",
            "Estado", "Desde");
    public static final List<String> TITULOS_HISTORICO = List.of("Código", "Descrição", "Data", "Estado", "Motivo");

    private ExcelExportacao() {
    }

    public static void maquinas(Path destino, List<Maquina> maquinas, Catalogos cat) throws IOException {
        List<List<Object>> linhas = new ArrayList<>(maquinas.size());
        for (Maquina m : maquinas) {
            linhas.add(java.util.Arrays.asList(m.codigo(), m.descricao(), m.nSerie(), nome(cat.categoria(m.categoriaId())),
                    nome(cat.localizacao(m.localizacaoId())), nome(cat.fornecedor(m.fornecedorId())),
                    m.dataAquisicao(), m.estado().toString()));
        }
        ExcelEscritor.escrever(destino, "Máquinas", TITULOS_MAQUINAS, linhas);
    }

    /** O stock atual é só informação (calculado pelos movimentos): ao importar só conta em artigos novos. */
    public static void artigos(Path destino, List<Artigo> artigos, Map<Long, Double> stocks, Catalogos cat)
            throws IOException {
        List<List<Object>> linhas = new ArrayList<>(artigos.size());
        for (Artigo a : artigos) {
            linhas.add(java.util.Arrays.asList(a.codigo(), a.descricao(), nome(cat.categoria(a.categoriaId())),
                    nome(cat.localizacao(a.localizacaoId())), nome(cat.fornecedor(a.fornecedorId())), a.unidade(),
                    a.stockMinimo(), stocks.getOrDefault(a.id(), 0.0)));
        }
        ExcelEscritor.escrever(destino, "Artigos", TITULOS_ARTIGOS, linhas);
    }

    public static void stockAtual(Path destino, List<StockArtigo> itens, Catalogos cat) throws IOException {
        List<List<Object>> linhas = new ArrayList<>(itens.size());
        for (StockArtigo s : itens) {
            Artigo a = s.artigo();
            linhas.add(java.util.Arrays.asList(a.codigo(), a.descricao(), nome(cat.categoria(a.categoriaId())),
                    nome(cat.localizacao(a.localizacaoId())), nome(cat.fornecedor(a.fornecedorId())), a.unidade(),
                    s.stock(), a.stockMinimo(), s.abaixoDoMinimo() ? "Abaixo do mínimo" : null));
        }
        ExcelEscritor.escrever(destino, "Stock atual", TITULOS_STOCK, linhas);
    }

    public static void movimentos(Path destino, List<MovimentoStock> itens, Map<Long, Artigo> artigos)
            throws IOException {
        List<List<Object>> linhas = new ArrayList<>(itens.size());
        for (MovimentoStock m : itens) {
            Artigo a = artigos.get(m.artigoId());
            linhas.add(java.util.Arrays.asList(m.dataMov(), a == null ? null : a.codigo(),
                    a == null ? null : a.descricao(), m.tipo().toString(), m.quantidade(),
                    a == null ? null : a.unidade(), m.nota()));
        }
        ExcelEscritor.escrever(destino, "Movimentos", TITULOS_MOVIMENTOS, linhas);
    }

    /** Os planos de cada máquina com a última e a próxima data; serve o itinerário e a lista de planos. */
    public static void planos(Path destino, String folha, List<PlanoDaMaquina> itens, LocalDate hoje, Catalogos cat)
            throws IOException {
        List<List<Object>> linhas = new ArrayList<>(itens.size());
        for (PlanoDaMaquina p : itens) {
            PlanoManutencao plano = p.prazo().plano();
            linhas.add(java.util.Arrays.asList(Prazos.situacao(p.prazo(), hoje), p.maquina().codigo(),
                    p.maquina().descricao(), nome(cat.categoria(p.maquina().categoriaId())), plano.tarefa(),
                    plano.periodicidadeMeses(), p.prazo().ultima(), p.prazo().proxima(), Prazos.prazo(p.prazo(), hoje)));
        }
        ExcelEscritor.escrever(destino, folha, TITULOS_PLANOS, linhas);
    }

    public static void intervencoes(Path destino, List<Intervencao> itens, Map<Long, Maquina> maquinas,
            Map<Long, PlanoManutencao> planos) throws IOException {
        List<List<Object>> linhas = new ArrayList<>(itens.size());
        for (Intervencao i : itens) {
            Maquina m = maquinas.get(i.maquinaId());
            PlanoManutencao p = i.planoId() == null ? null : planos.get(i.planoId());
            linhas.add(java.util.Arrays.asList(i.dataInterv(), m == null ? null : m.codigo(),
                    m == null ? null : m.descricao(), i.tipo().toString(), p == null ? null : p.tarefa(),
                    i.descricao(), i.custo()));
        }
        ExcelEscritor.escrever(destino, "Intervenções", TITULOS_INTERVENCOES, linhas);
    }

    public static void estados(Path destino, List<Maquina> itens, Map<Long, LocalDate> desde, Catalogos cat)
            throws IOException {
        List<List<Object>> linhas = new ArrayList<>(itens.size());
        for (Maquina m : itens) {
            linhas.add(java.util.Arrays.asList(m.codigo(), m.descricao(), nome(cat.categoria(m.categoriaId())),
                    nome(cat.localizacao(m.localizacaoId())), m.estado().toString(), desde.get(m.id())));
        }
        ExcelEscritor.escrever(destino, "Estados", TITULOS_ESTADOS, linhas);
    }

    /** O histórico de estados de várias máquinas, por máquina e do mais antigo para o mais recente. */
    public static void historico(Path destino, List<HistoricoEstado> itens, Map<Long, Maquina> maquinas)
            throws IOException {
        List<List<Object>> linhas = new ArrayList<>(itens.size());
        for (HistoricoEstado h : itens) {
            Maquina m = maquinas.get(h.maquinaId());
            linhas.add(java.util.Arrays.asList(m == null ? null : m.codigo(), m == null ? null : m.descricao(),
                    h.dataEstado(), h.estado().toString(), h.motivo()));
        }
        ExcelEscritor.escrever(destino, "Histórico de estados", TITULOS_HISTORICO, linhas);
    }

    private static String nome(String nome) {
        return nome == null || nome.isEmpty() ? null : nome;
    }
}
