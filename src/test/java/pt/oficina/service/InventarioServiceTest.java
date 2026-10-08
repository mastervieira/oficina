package pt.oficina.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.oficina.db.Database;
import pt.oficina.model.Artigo;
import pt.oficina.model.Categoria;
import pt.oficina.model.Maquina;
import pt.oficina.model.enums.EstadoMaquina;
import pt.oficina.model.enums.TipoCategoria;

class InventarioServiceTest {

    private Database db;
    private InventarioService svc;
    private Categoria catMaq;
    private Categoria catArt;

    @BeforeEach
    void preparar() throws SQLException {
        db = Database.inMemory();
        svc = new InventarioService(db.connection(), Relogios.em(LocalDate.of(2026, 10, 4)));
        catMaq = svc.criarCategoria("Tornos", TipoCategoria.MAQUINA);
        catArt = svc.criarCategoria("Pastilhas", TipoCategoria.ARTIGO);
    }

    @AfterEach
    void fechar() throws SQLException {
        db.close();
    }

    private Maquina maquina(Long id, String codigo) {
        return new Maquina(id, codigo, "Torno paralelo", "  ", catMaq.id(), null, null,
                LocalDate.of(2020, 5, 1), EstadoMaquina.ABATIDO);
    }

    private Artigo artigo(Long id, String codigo, String unidade, double min) {
        return new Artigo(id, codigo, "Pastilha CNMG", catArt.id(), null, null, unidade, min);
    }

    private int contar(String sql) throws SQLException {
        try (Statement st = db.connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void novaMaquinaFicaAtivaComHistoricoInicial() throws SQLException {
        Maquina m = svc.guardarMaquina(maquina(null, " T01 "));
        assertNotNull(m.id());
        assertEquals("T01", m.codigo());
        assertNull(m.nSerie());
        assertEquals(EstadoMaquina.ATIVO, m.estado()); // ignora o estado recebido
        assertEquals(1, contar("SELECT COUNT(*) FROM historico_estado WHERE maquina_id = " + m.id()
                + " AND estado = 'ATIVO'"));
    }

    @Test
    void editarMaquinaNaoMexeNoEstadoNemNoHistorico() throws SQLException {
        Maquina m = svc.guardarMaquina(maquina(null, "T01"));
        try (Statement st = db.connection().createStatement()) {
            st.execute("UPDATE maquina SET estado = 'INOPERATIVO' WHERE id = " + m.id());
        }
        Maquina editada = svc.guardarMaquina(new Maquina(m.id(), "T01", "Nova descrição", null, catMaq.id(),
                null, null, null, EstadoMaquina.ATIVO));
        assertEquals("Nova descrição", editada.descricao());
        assertEquals(EstadoMaquina.INOPERATIVO, editada.estado());
        assertEquals(1, contar("SELECT COUNT(*) FROM historico_estado"));
    }

    @Test
    void codigoDeMaquinaDuplicadoRejeitado() throws SQLException {
        svc.guardarMaquina(maquina(null, "T01"));
        assertThrows(ValidacaoException.class, () -> svc.guardarMaquina(maquina(null, "t01")));
        assertEquals(1, contar("SELECT COUNT(*) FROM maquina"));
        assertEquals(1, contar("SELECT COUNT(*) FROM historico_estado"));
    }

    @Test
    void categoriaDoTipoErradoRejeitada() {
        Maquina comCategoriaDeArtigo = new Maquina(null, "T01", "x", null, catArt.id(), null, null, null, null);
        assertThrows(ValidacaoException.class, () -> svc.guardarMaquina(comCategoriaDeArtigo));
        Artigo comCategoriaDeMaquina = new Artigo(null, "A01", "x", catMaq.id(), null, null, "un", 0);
        assertThrows(ValidacaoException.class, () -> svc.guardarArtigo(comCategoriaDeMaquina));
    }

    @Test
    void camposObrigatorios() {
        assertThrows(ValidacaoException.class, () -> svc.guardarMaquina(maquina(null, "  ")));
        assertThrows(ValidacaoException.class, () -> svc.guardarArtigo(artigo(null, "", "un", 0)));
    }

    @Test
    void mensagensDeCampoObrigatorioConcordamComOGenero() {
        assertEquals("O código é obrigatório.", ValidacaoException.obrigatorio("O código").getMessage());
        assertEquals("A descrição é obrigatória.", ValidacaoException.obrigatorio("A descrição").getMessage());
        Maquina semDescricao = new Maquina(null, "T01", "  ", null, catMaq.id(), null, null, null, null);
        assertEquals("A descrição é obrigatória.",
                assertThrows(ValidacaoException.class, () -> svc.guardarMaquina(semDescricao)).getMessage());
    }

    @Test
    void artigoValidaUnidadeEStockMinimo() throws SQLException {
        assertThrows(ValidacaoException.class, () -> svc.guardarArtigo(artigo(null, "A01", "caixote", 0)));
        assertThrows(ValidacaoException.class, () -> svc.guardarArtigo(artigo(null, "A01", "un", -1)));
        Artigo a = svc.guardarArtigo(artigo(null, "A01", "un", 5));
        assertNotNull(a.id());
        Artigo editado = svc.guardarArtigo(artigo(a.id(), "A01", "cx", 2.5));
        assertEquals("cx", editado.unidade());
        assertEquals(2.5, editado.stockMinimo());
        assertThrows(ValidacaoException.class, () -> svc.guardarArtigo(artigo(null, "a01", "un", 0)));
    }

    @Test
    void dataDeAquisicaoFuturaELimiteDoStockMinimo() throws SQLException {
        Maquina futura = new Maquina(null, "T09", "x", null, catMaq.id(), null, null, LocalDate.of(2026, 10, 5), null);
        assertThrows(ValidacaoException.class, () -> svc.guardarMaquina(futura));
        Maquina hoje = new Maquina(null, "T09", "x", null, catMaq.id(), null, null, LocalDate.of(2026, 10, 4), null);
        assertNotNull(svc.guardarMaquina(hoje).id());
        assertThrows(ValidacaoException.class, () -> svc.guardarArtigo(artigo(null, "A02", "un", 1e12)));
        assertThrows(ValidacaoException.class, () -> svc.guardarArtigo(artigo(null, "A02", "un", Double.NaN)));
    }

    @Test
    void categoriasDeArtigosTemVariosTiposMasUmaSoFamilia() throws SQLException {
        Categoria ferramentas = svc.criarCategoria("Brocas", TipoCategoria.FERRAMENTA);
        Categoria consumiveis = svc.criarCategoria("Óleos", TipoCategoria.CONSUMIVEL);
        svc.criarCategoria("Vários", TipoCategoria.OUTROS);

        // O nome é único em toda a família de artigos, seja qual for o tipo (senão apareceria duas vezes no combo).
        assertThrows(ValidacaoException.class, () -> svc.criarCategoria("pastilhas", TipoCategoria.CONSUMIVEL));
        assertThrows(ValidacaoException.class, () -> svc.criarCategoria("BROCAS", TipoCategoria.OUTROS));
        assertThrows(ValidacaoException.class, () -> svc.renomearCategoria(consumiveis.id(), "Brocas"));
        svc.criarCategoria("Tornos", TipoCategoria.FERRAMENTA); // existe em máquinas: outra família, é permitido

        Catalogos cat = svc.catalogos();
        assertEquals(List.of("Brocas", "Óleos", "Pastilhas", "Tornos", "Vários"),
                cat.categoriasArtigo().stream().map(Categoria::nome).toList());
        assertEquals(List.of("Tornos"), cat.categoriasMaquina().stream().map(Categoria::nome).toList());

        // Artigos aceitam qualquer tipo de categoria de artigos; máquinas só as de máquinas.
        assertNotNull(svc.guardarArtigo(new Artigo(null, "B01", "Broca 6", ferramentas.id(), null, null, "un", 0)).id());
        assertNotNull(svc.guardarArtigo(new Artigo(null, "O01", "Óleo", consumiveis.id(), null, null, "l", 0)).id());
        Maquina comCategoriaDeFerramentas = new Maquina(null, "T07", "x", null, ferramentas.id(), null, null, null, null);
        assertThrows(ValidacaoException.class, () -> svc.guardarMaquina(comCategoriaDeFerramentas));
    }

    @Test
    void categoriaMostraOTipoNasListasQuandoEEspecifico() {
        assertEquals("Pastilhas", new Categoria(1L, "Pastilhas", TipoCategoria.ARTIGO).toString());
        assertEquals("Tornos", new Categoria(2L, "Tornos", TipoCategoria.MAQUINA).toString());
        assertEquals("Brocas (Ferramenta)", new Categoria(3L, "Brocas", TipoCategoria.FERRAMENTA).toString());
        assertEquals("Óleos (Consumível)", new Categoria(4L, "Óleos", TipoCategoria.CONSUMIVEL).toString());
    }

    @Test
    void apagarArtigoSoSeNuncaTeveMovimentos() throws SQLException {
        Artigo novo = svc.guardarArtigo(artigo(null, "A10", "un", 0));
        svc.apagarArtigo(novo.id());
        assertEquals(0, svc.artigos().stream().filter(a -> a.id().equals(novo.id())).count());

        StockService stock = new StockService(db.connection(), Relogios.em(LocalDate.of(2026, 10, 4)));
        Artigo usado = svc.guardarArtigo(artigo(null, "A11", "un", 0));
        stock.registar(usado.id(), pt.oficina.model.enums.TipoMovimento.ENTRADA, LocalDate.of(2026, 10, 1), 5, null);
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> svc.apagarArtigo(usado.id()));
        assertTrue(e.getMessage().contains("movimentos de stock"), e.getMessage());
        assertEquals(1, svc.artigos().stream().filter(a -> a.id().equals(usado.id())).count());
        assertTrue(db.connection().getAutoCommit(), "a ligação fica utilizável");
        svc.apagarArtigo(999_999); // inexistente: nada a fazer
    }

    @Test
    void apagarMaquinaNovaApagaTambemORegistoInicial() throws SQLException {
        Maquina m = svc.guardarMaquina(maquina(null, "T20"));
        assertEquals(1, contar("SELECT COUNT(*) FROM historico_estado WHERE maquina_id = " + m.id()));
        svc.apagarMaquina(m.id());
        assertEquals(0, contar("SELECT COUNT(*) FROM maquina WHERE id = " + m.id()));
        assertEquals(0, contar("SELECT COUNT(*) FROM historico_estado WHERE maquina_id = " + m.id()));
    }

    @Test
    void maquinaComMudancasDeEstadoNaoSeApagaMasAbate() throws SQLException {
        Maquina m = svc.guardarMaquina(maquina(null, "T21"));
        EstadoMaquinaService estados = new EstadoMaquinaService(db.connection(), Relogios.em(LocalDate.of(2026, 10, 4)));
        estados.mudarEstado(m.id(), EstadoMaquina.INOPERATIVO, LocalDate.of(2026, 10, 4), "Avaria");
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> svc.apagarMaquina(m.id()));
        assertTrue(e.getMessage().contains("Abatido"), e.getMessage());
        assertEquals(2, contar("SELECT COUNT(*) FROM historico_estado WHERE maquina_id = " + m.id()));
        estados.mudarEstado(m.id(), EstadoMaquina.ABATIDO, LocalDate.of(2026, 10, 4), "Fim de vida"); // o caminho certo
    }

    @Test
    void falharAoApagarMaquinaDesfazTudoInclusiveOHistoricoJaApagado() throws SQLException {
        // Máquina nova (1 linha de histórico) mas com uma intervenção: o histórico é apagado primeiro e só depois a
        // chave estrangeira recusa a máquina. A transação tem de repor a linha apagada.
        Maquina m = svc.guardarMaquina(maquina(null, "T22"));
        ManutencaoService man = new ManutencaoService(db.connection(),
                new StockService(db.connection()), Relogios.em(LocalDate.of(2026, 10, 4)));
        man.registarIntervencao(new pt.oficina.model.Intervencao(null, m.id(), null, LocalDate.of(2026, 10, 3),
                pt.oficina.model.enums.TipoIntervencao.CORRETIVA, "Avaria", null), java.util.List.of());
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> svc.apagarMaquina(m.id()));
        assertTrue(e.getMessage().contains("tem intervenções registadas"), e.getMessage());
        assertEquals(1, contar("SELECT COUNT(*) FROM maquina WHERE id = " + m.id()));
        assertEquals(1, contar("SELECT COUNT(*) FROM historico_estado WHERE maquina_id = " + m.id()),
                "o registo inicial tem de voltar atrás");
        assertTrue(db.connection().getAutoCommit());
    }

    @Test
    void listasDeApoio() throws SQLException {
        assertThrows(ValidacaoException.class, () -> svc.criarCategoria("tornos", TipoCategoria.MAQUINA));
        svc.criarCategoria("Tornos", TipoCategoria.ARTIGO); // mesmo nome noutro tipo é permitido
        svc.criarLocalizacao("Armazém");
        assertThrows(ValidacaoException.class, () -> svc.criarLocalizacao("armazém"));
        var f = svc.criarFornecedor("Sandvik", " 210 000 000 ");
        assertEquals("210 000 000", f.contacto());
        svc.atualizarFornecedor(f.id(), "Sandvik Portugal", "");
        svc.renomearCategoria(catMaq.id(), "Tornos CNC");

        Catalogos cat = svc.catalogos();
        assertEquals("Tornos CNC", cat.categoria(catMaq.id()));
        assertEquals("Sandvik Portugal", cat.fornecedor(f.id()));
        assertEquals("", cat.localizacao(null));
    }

    @Test
    void imagemDoArtigoGravaSeLeSeERetiraSe() throws SQLException {
        Artigo a = svc.guardarArtigo(artigo(null, "A01", "un", 0));
        assertNull(svc.imagemArtigo(a.id())); // sem imagem por defeito

        byte[] foto = png(4, 3);
        svc.guardarArtigo(artigo(a.id(), "A01", "un", 0), true, foto);
        assertArrayEquals(foto, svc.imagemArtigo(a.id()));

        // gravar sem mexer na imagem não a apaga (é o caso da importação do Excel e das edições sem alteração)
        svc.guardarArtigo(artigo(a.id(), "A01", "un", 3));
        svc.guardarArtigo(artigo(a.id(), "A01", "un", 4), false, null);
        assertArrayEquals(foto, svc.imagemArtigo(a.id()));

        svc.guardarArtigo(artigo(a.id(), "A01", "un", 4), true, null);
        assertNull(svc.imagemArtigo(a.id()));
    }

    @Test
    void novoArtigoPodeJaTerImagem() throws SQLException {
        byte[] foto = jpeg(2, 2);
        Artigo a = svc.guardarArtigo(artigo(null, "A02", "un", 0), true, foto);
        assertArrayEquals(foto, svc.imagemArtigo(a.id()));
    }

    @Test
    void imagemVaziaOuDemasiadoGrandeRejeitadaENadaSeGrava() throws SQLException {
        assertThrows(ValidacaoException.class,
                () -> svc.guardarArtigo(artigo(null, "A03", "un", 0), true, new byte[0]));
        assertThrows(ValidacaoException.class, () -> svc.guardarArtigo(artigo(null, "A03", "un", 0), true,
                new byte[InventarioService.MAX_IMAGEM_BYTES + 1]));
        assertEquals(0, contar("SELECT COUNT(*) FROM artigo"));
    }

    @Test
    void imagemDaMaquinaGravaSeLeSeERetiraSe() throws SQLException {
        Maquina m = svc.guardarMaquina(maquina(null, "T30"));
        assertNull(svc.imagemMaquina(m.id())); // sem imagem por defeito

        byte[] foto = png(4, 3);
        svc.guardarMaquina(maquina(m.id(), "T30"), true, foto);
        assertArrayEquals(foto, svc.imagemMaquina(m.id()));

        // gravar sem mexer na imagem não a apaga (é o caso da importação do Excel e das edições sem alteração)
        svc.guardarMaquina(new Maquina(m.id(), "T30", "Outra descrição", null, catMaq.id(), null, null, null, null));
        svc.guardarMaquina(maquina(m.id(), "T30"), false, null);
        assertArrayEquals(foto, svc.imagemMaquina(m.id()));

        svc.guardarMaquina(maquina(m.id(), "T30"), true, null);
        assertNull(svc.imagemMaquina(m.id()));
    }

    @Test
    void novaMaquinaPodeJaTerImagemEFicaComHistoricoInicial() throws SQLException {
        byte[] foto = jpeg(2, 2);
        Maquina m = svc.guardarMaquina(maquina(null, "T31"), true, foto);
        assertArrayEquals(foto, svc.imagemMaquina(m.id()));
        assertEquals(1, contar("SELECT COUNT(*) FROM historico_estado WHERE maquina_id = " + m.id()));
    }

    @Test
    void imagemInvalidaNaMaquinaRejeitadaENadaSeGrava() throws SQLException {
        assertThrows(ValidacaoException.class, () -> svc.guardarMaquina(maquina(null, "T32"), true, new byte[0]));
        assertThrows(ValidacaoException.class,
                () -> svc.guardarMaquina(maquina(null, "T32"), true, new byte[] {1, 2, 3, 4}));
        assertThrows(ValidacaoException.class,
                () -> svc.guardarMaquina(maquina(null, "T32"), true, cabecalhoPng(30_000, 30_000)));
        assertEquals(0, contar("SELECT COUNT(*) FROM maquina"));
        assertEquals(0, contar("SELECT COUNT(*) FROM historico_estado"));

        // na edição, uma imagem recusada não altera nem os dados nem a imagem que já lá estava
        byte[] foto = png(2, 2);
        Maquina m = svc.guardarMaquina(maquina(null, "T33"), true, foto);
        assertThrows(ValidacaoException.class, () -> svc.guardarMaquina(
                new Maquina(m.id(), "T33", "Mudada", null, catMaq.id(), null, null, null, null), true, new byte[] {1}));
        assertEquals("Torno paralelo", svc.maquinas().get(0).descricao());
        assertArrayEquals(foto, svc.imagemMaquina(m.id()));
    }

    // ---- imagens -------------------------------------------------------------

    private static byte[] imagem(int largura, int altura, String formato) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB), formato, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] png(int largura, int altura) {
        return imagem(largura, altura, "png");
    }

    private static byte[] jpeg(int largura, int altura) {
        return imagem(largura, altura, "jpeg");
    }

    /** Só a assinatura e o cabeçalho IHDR de um PNG: poucos bytes que anunciam {@code largura} x {@code altura} píxeis. */
    private static byte[] cabecalhoPng(int largura, int altura) {
        ByteBuffer ihdr = ByteBuffer.allocate(17).put("IHDR".getBytes()).putInt(largura).putInt(altura)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        return ByteBuffer.allocate(8 + 4 + 17 + 4)
                .put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})
                .putInt(13).put(ihdr.array()).putInt((int) crc.getValue()).array();
    }

    @Test
    void imagemQueNaoEImagemERecusada() {
        assertThrows(ValidacaoException.class,
                () -> svc.guardarArtigo(artigo(null, "A04", "un", 0), true, new byte[] {1, 2, 3, 4}));
        assertThrows(ValidacaoException.class, () -> InventarioService.validarImagem("<svg/>".getBytes()));
    }

    @Test
    void imagemComPixeisDemaisERecusadaSemSerDescodificada() throws SQLException {
        // poucos bytes, mas 30 000 x 30 000 píxeis: mostrá-la ocuparia mais de 3 GB de memória
        byte[] bomba = cabecalhoPng(30_000, 30_000);
        assertTrue(bomba.length < 100);
        ValidacaoException e = assertThrows(ValidacaoException.class,
                () -> svc.guardarArtigo(artigo(null, "A05", "un", 0), true, bomba));
        assertTrue(e.getMessage().contains("30000 x 30000"), e.getMessage());
        // cada lado dentro do limite, mas o total de píxeis acima (9000 x 9000 = 81 MP)
        assertThrows(ValidacaoException.class, () -> InventarioService.validarImagem(cabecalhoPng(9_000, 9_000)));
        // no limite: aceite (só se lê o cabeçalho)
        InventarioService.validarImagem(cabecalhoPng(InventarioService.MAX_IMAGEM_LADO, 5_000));
        assertEquals(0, contar("SELECT COUNT(*) FROM artigo"));
    }

    @Test
    void renomearOuAlterarUmRegistoQueJaNaoExisteAvisa() {
        assertThrows(ValidacaoException.class, () -> svc.renomearLocalizacao(999, "Armazém"));
        assertThrows(ValidacaoException.class, () -> svc.atualizarFornecedor(999, "Sandvik", null));
    }
}
