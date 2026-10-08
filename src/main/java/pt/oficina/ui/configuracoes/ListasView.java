package pt.oficina.ui.configuracoes;

import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import pt.oficina.model.Categoria;
import pt.oficina.model.Fornecedor;
import pt.oficina.model.Localizacao;
import pt.oficina.model.enums.TipoCategoria;
import pt.oficina.service.InventarioService;

/** Manutenção das listas de apoio (categorias, localizações, fornecedores) usadas nos formulários. */
public final class ListasView extends GridPane {

    private final ListaPane<Categoria> categoriasMaquina;
    private final ListaPane<Categoria> categoriasArtigo;
    private final ListaPane<Localizacao> localizacoes;
    private final ListaPane<Fornecedor> fornecedores;

    public ListasView(InventarioService svc) {
        categoriasMaquina = new ListaPane<>("Categorias de máquinas",
                () -> svc.catalogos().categoriasMaquina(), Categoria::nome,
                (atual, c) -> {
                    if (atual == null) {
                        svc.criarCategoria(c.nome(), TipoCategoria.MAQUINA);
                    } else {
                        svc.renomearCategoria(atual.id(), c.nome());
                    }
                });
        categoriasArtigo = new ListaPane<Categoria>("Categorias de artigos",
                () -> svc.catalogos().categoriasArtigo(), Categoria::nome,
                (atual, c) -> {
                    if (atual == null) {
                        svc.criarCategoria(c.nome(), c.tipo());
                    } else {
                        svc.renomearCategoria(atual.id(), c.nome());
                    }
                }).comTipo(TipoCategoria.deArtigos(), Categoria::tipo);
        localizacoes = new ListaPane<>("Localizações",
                () -> svc.catalogos().localizacoes(), Localizacao::nome,
                (atual, c) -> {
                    if (atual == null) {
                        svc.criarLocalizacao(c.nome());
                    } else {
                        svc.renomearLocalizacao(atual.id(), c.nome());
                    }
                });
        fornecedores = new ListaPane<Fornecedor>("Fornecedores",
                () -> svc.catalogos().fornecedores(), Fornecedor::nome,
                (atual, c) -> {
                    if (atual == null) {
                        svc.criarFornecedor(c.nome(), c.contacto());
                    } else {
                        svc.atualizarFornecedor(atual.id(), c.nome(), c.contacto());
                    }
                }).comContacto(Fornecedor::contacto);

        add(categoriasMaquina, 0, 0);
        add(categoriasArtigo, 1, 0);
        add(localizacoes, 0, 1);
        add(fornecedores, 1, 1);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(50);
            getColumnConstraints().add(col);
            RowConstraints row = new RowConstraints();
            row.setPercentHeight(50);
            row.setVgrow(Priority.ALWAYS);
            getRowConstraints().add(row);
        }
        atualizar();
    }

    public void atualizar() {
        categoriasMaquina.atualizar();
        categoriasArtigo.atualizar();
        localizacoes.atualizar();
        fornecedores.atualizar();
    }
}
