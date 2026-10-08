-- Esquema da oficina (SQLite). Os blocos são separados por linhas "-- @@"
-- porque os triggers contêm ';' internos. Aplicado por SchemaInitializer
-- quando PRAGMA user_version = 0.
-- Datas: TEXT ISO-8601 (yyyy-MM-dd). Quantidades: REAL.

CREATE TABLE categoria (
    id    INTEGER PRIMARY KEY AUTOINCREMENT,
    nome  TEXT NOT NULL,
    tipo  TEXT NOT NULL CHECK (tipo IN ('MAQUINA','ARTIGO')),
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
                    CHECK (estado IN ('ATIVO','INOPERATIVO','ABATIDO'))
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
    stock_minimo    REAL NOT NULL DEFAULT 0 CHECK (stock_minimo >= 0)
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
CREATE TABLE plano_manutencao (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    maquina_id           INTEGER NOT NULL REFERENCES maquina(id),
    tarefa               TEXT NOT NULL,
    periodicidade_meses  INTEGER NOT NULL CHECK (periodicidade_meses > 0)
);
-- @@
CREATE TABLE intervencao (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    maquina_id   INTEGER NOT NULL REFERENCES maquina(id),
    plano_id     INTEGER REFERENCES plano_manutencao(id),
    data_interv  TEXT NOT NULL,
    tipo         TEXT NOT NULL CHECK (tipo IN ('PREVENTIVA','CORRETIVA')),
    descricao    TEXT,
    custo        REAL CHECK (custo IS NULL OR custo >= 0)
);
-- @@
CREATE TABLE historico_estado (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    maquina_id   INTEGER NOT NULL REFERENCES maquina(id),
    data_estado  TEXT NOT NULL,
    estado       TEXT NOT NULL CHECK (estado IN ('ATIVO','INOPERATIVO','ABATIDO')),
    motivo       TEXT
);
-- @@
CREATE INDEX idx_movimento_artigo    ON movimento_stock(artigo_id);
-- @@
CREATE INDEX idx_plano_maquina       ON plano_manutencao(maquina_id);
-- @@
CREATE INDEX idx_intervencao_plano   ON intervencao(plano_id, data_interv);
-- @@
CREATE INDEX idx_intervencao_maquina ON intervencao(maquina_id);
-- @@
CREATE INDEX idx_historico_maquina   ON historico_estado(maquina_id, data_estado);
-- @@

-- Nunca apagar máquinas nem artigos: muda-se o estado.
CREATE TRIGGER trg_maquina_sem_delete BEFORE DELETE ON maquina
BEGIN
    SELECT RAISE(ABORT, 'Maquinas nao podem ser apagadas; altere o estado.');
END;
-- @@
CREATE TRIGGER trg_artigo_sem_delete BEFORE DELETE ON artigo
BEGIN
    SELECT RAISE(ABORT, 'Artigos nao podem ser apagados.');
END;
-- @@

-- O histórico de estados só se acrescenta.
CREATE TRIGGER trg_historico_sem_update BEFORE UPDATE ON historico_estado
BEGIN
    SELECT RAISE(ABORT, 'O historico de estados so pode ser acrescentado.');
END;
-- @@
CREATE TRIGGER trg_historico_sem_delete BEFORE DELETE ON historico_estado
BEGIN
    SELECT RAISE(ABORT, 'O historico de estados so pode ser acrescentado.');
END;
-- @@

-- Uma intervenção ligada a um plano tem de ser da mesma máquina do plano.
CREATE TRIGGER trg_intervencao_plano_maquina BEFORE INSERT ON intervencao
WHEN NEW.plano_id IS NOT NULL
 AND (SELECT maquina_id FROM plano_manutencao WHERE id = NEW.plano_id) <> NEW.maquina_id
BEGIN
    SELECT RAISE(ABORT, 'O plano nao pertence a esta maquina.');
END;
-- @@
CREATE TRIGGER trg_intervencao_plano_maquina_upd BEFORE UPDATE ON intervencao
WHEN NEW.plano_id IS NOT NULL
 AND (SELECT maquina_id FROM plano_manutencao WHERE id = NEW.plano_id) <> NEW.maquina_id
BEGIN
    SELECT RAISE(ABORT, 'O plano nao pertence a esta maquina.');
END;
