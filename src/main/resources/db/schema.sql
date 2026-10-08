-- Esquema da oficina (SQLite), versão 6. Os blocos são separados por linhas "-- @@"
-- porque os triggers contêm ';' internos. Aplicado por SchemaInitializer quando PRAGMA user_version = 0;
-- bases de versões anteriores são migradas por db/migracao-N.sql (manter os ficheiros coerentes: há um teste
-- que compara o resultado com este esquema).
-- Datas: TEXT ISO-8601 (yyyy-MM-dd). Quantidades: REAL.

CREATE TABLE categoria (
    id    INTEGER PRIMARY KEY AUTOINCREMENT,
    nome  TEXT NOT NULL,
    tipo  TEXT NOT NULL CHECK (tipo IN ('MAQUINA','ARTIGO','FERRAMENTA','CONSUMIVEL','OUTROS')),
    UNIQUE (nome, tipo)
);
-- @@
CREATE TABLE localizacao (
    id    INTEGER PRIMARY KEY AUTOINCREMENT,
    nome  TEXT NOT NULL UNIQUE
);
-- @@
CREATE TABLE fornecedor (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    nome      TEXT NOT NULL UNIQUE,
    contacto  TEXT
);
-- @@
CREATE TABLE maquina (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    codigo          TEXT NOT NULL UNIQUE,
    descricao       TEXT NOT NULL,
    n_serie         TEXT,
    categoria_id    INTEGER NOT NULL REFERENCES categoria(id),
    localizacao_id  INTEGER REFERENCES localizacao(id),
    fornecedor_id   INTEGER REFERENCES fornecedor(id),
    data_aquisicao  TEXT,
    estado          TEXT NOT NULL DEFAULT 'ATIVO'
                    CHECK (estado IN ('ATIVO','INOPERATIVO','MANUTENCAO','ABATIDO')),
    imagem          BLOB
);
-- @@
CREATE TABLE artigo (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    codigo          TEXT NOT NULL UNIQUE,
    descricao       TEXT NOT NULL,
    categoria_id    INTEGER NOT NULL REFERENCES categoria(id),
    localizacao_id  INTEGER REFERENCES localizacao(id),
    fornecedor_id   INTEGER REFERENCES fornecedor(id),
    unidade         TEXT NOT NULL DEFAULT 'un',
    stock_minimo    REAL NOT NULL DEFAULT 0 CHECK (stock_minimo >= 0),
    imagem          BLOB
);
-- @@
CREATE TABLE movimento_stock (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    artigo_id   INTEGER NOT NULL REFERENCES artigo(id),
    data_mov    TEXT NOT NULL,
    tipo        TEXT NOT NULL CHECK (tipo IN ('ENTRADA','SAIDA')),
    quantidade  REAL NOT NULL CHECK (quantidade > 0),
    nota        TEXT
);
-- @@
-- Plano de manutenção de um TIPO de máquina (categoria de máquinas): vale para todas as máquinas desse tipo.
-- A próxima data é por máquina: última intervenção da máquina ligada ao plano + periodicidade.
CREATE TABLE plano_manutencao (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    categoria_id         INTEGER NOT NULL REFERENCES categoria(id),
    tarefa               TEXT NOT NULL,
    periodicidade_meses  INTEGER NOT NULL CHECK (periodicidade_meses > 0),
    UNIQUE (categoria_id, tarefa COLLATE NOCASE)
);
-- @@
CREATE TABLE intervencao (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    maquina_id   INTEGER NOT NULL REFERENCES maquina(id),
    plano_id     INTEGER REFERENCES plano_manutencao(id),
    data_interv  TEXT NOT NULL,
    tipo         TEXT NOT NULL CHECK (tipo IN ('PREVENTIVA','PROGRAMADA','CORRETIVA')),
    descricao    TEXT,
    custo        REAL CHECK (custo IS NULL OR custo >= 0)
);
-- @@
CREATE TABLE historico_estado (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    maquina_id   INTEGER NOT NULL REFERENCES maquina(id),
    data_estado  TEXT NOT NULL,
    estado       TEXT NOT NULL CHECK (estado IN ('ATIVO','INOPERATIVO','MANUTENCAO','ABATIDO')),
    motivo       TEXT
);
-- @@
CREATE INDEX idx_movimento_artigo    ON movimento_stock(artigo_id);
-- @@
CREATE INDEX idx_plano_categoria     ON plano_manutencao(categoria_id);
-- @@
CREATE INDEX idx_intervencao_plano   ON intervencao(plano_id, data_interv);
-- @@
CREATE INDEX idx_intervencao_maquina ON intervencao(maquina_id);
-- @@
CREATE INDEX idx_historico_maquina   ON historico_estado(maquina_id, data_estado);
-- @@

-- O histórico de estados só se acrescenta: nunca se altera, e só se apaga a linha única de uma máquina
-- (o registo inicial de uma máquina que nunca mudou de estado e vai ser eliminada).
CREATE TRIGGER trg_historico_sem_update BEFORE UPDATE ON historico_estado
BEGIN
    SELECT RAISE(ABORT, 'O historico de estados so pode ser acrescentado.');
END;
-- @@
CREATE TRIGGER trg_historico_sem_delete BEFORE DELETE ON historico_estado
WHEN (SELECT COUNT(*) FROM historico_estado WHERE maquina_id = OLD.maquina_id) > 1
BEGIN
    SELECT RAISE(ABORT, 'O historico de estados so pode ser acrescentado.');
END;
-- @@

-- Uma intervenção ligada a um plano tem de ser de uma máquina do tipo a que o plano pertence.
CREATE TRIGGER trg_intervencao_plano_tipo BEFORE INSERT ON intervencao
WHEN NEW.plano_id IS NOT NULL
 AND (SELECT categoria_id FROM plano_manutencao WHERE id = NEW.plano_id)
  <> (SELECT categoria_id FROM maquina WHERE id = NEW.maquina_id)
BEGIN
    SELECT RAISE(ABORT, 'O plano nao se aplica ao tipo desta maquina.');
END;
-- @@
CREATE TRIGGER trg_intervencao_plano_tipo_upd BEFORE UPDATE ON intervencao
WHEN NEW.plano_id IS NOT NULL
 AND (SELECT categoria_id FROM plano_manutencao WHERE id = NEW.plano_id)
  <> (SELECT categoria_id FROM maquina WHERE id = NEW.maquina_id)
BEGIN
    SELECT RAISE(ABORT, 'O plano nao se aplica ao tipo desta maquina.');
END;
