-- Migração 1 -> 2: passam a ser aceites os valores
--   categoria.tipo:                FERRAMENTA, CONSUMIVEL, OUTROS (além de MAQUINA, ARTIGO)
--   maquina.estado / historico_estado.estado: MANUTENCAO
--   intervencao.tipo:              PROGRAMADA
-- O SQLite não altera CHECK: reconstroem-se as 4 tabelas afetadas pelo procedimento oficial (criar a nova,
-- copiar, apagar a antiga, renomear, recriar índices e triggers). Corre com foreign_keys = OFF, dentro de uma
-- transação, e o SchemaInitializer termina com PRAGMA foreign_key_check (se falhar, desfaz tudo).
-- As definições vêm de schema.sql: manter os dois ficheiros coerentes (há um teste que compara o resultado).

CREATE TABLE categoria_nova (
    id    INTEGER PRIMARY KEY AUTOINCREMENT,
    nome  TEXT NOT NULL,
    tipo  TEXT NOT NULL CHECK (tipo IN ('MAQUINA','ARTIGO','FERRAMENTA','CONSUMIVEL','OUTROS')),
    UNIQUE (nome, tipo)
);
-- @@
INSERT INTO categoria_nova(id, nome, tipo) SELECT id, nome, tipo FROM categoria;
-- @@
DROP TABLE categoria;
-- @@
ALTER TABLE categoria_nova RENAME TO categoria;
-- @@
CREATE TABLE maquina_nova (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    codigo          TEXT NOT NULL UNIQUE,
    descricao       TEXT NOT NULL,
    n_serie         TEXT,
    categoria_id    INTEGER NOT NULL REFERENCES categoria(id),
    localizacao_id  INTEGER REFERENCES localizacao(id),
    fornecedor_id   INTEGER REFERENCES fornecedor(id),
    data_aquisicao  TEXT,
    estado          TEXT NOT NULL DEFAULT 'ATIVO'
                    CHECK (estado IN ('ATIVO','INOPERATIVO','MANUTENCAO','ABATIDO'))
);
-- @@
INSERT INTO maquina_nova(id, codigo, descricao, n_serie, categoria_id, localizacao_id, fornecedor_id, data_aquisicao, estado) SELECT id, codigo, descricao, n_serie, categoria_id, localizacao_id, fornecedor_id, data_aquisicao, estado FROM maquina;
-- @@
DROP TABLE maquina;
-- @@
ALTER TABLE maquina_nova RENAME TO maquina;
-- @@
CREATE TRIGGER trg_maquina_sem_delete BEFORE DELETE ON maquina
BEGIN
    SELECT RAISE(ABORT, 'Maquinas nao podem ser apagadas; altere o estado.');
END;
-- @@
CREATE TABLE intervencao_nova (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    maquina_id   INTEGER NOT NULL REFERENCES maquina(id),
    plano_id     INTEGER REFERENCES plano_manutencao(id),
    data_interv  TEXT NOT NULL,
    tipo         TEXT NOT NULL CHECK (tipo IN ('PREVENTIVA','PROGRAMADA','CORRETIVA')),
    descricao    TEXT,
    custo        REAL CHECK (custo IS NULL OR custo >= 0)
);
-- @@
INSERT INTO intervencao_nova(id, maquina_id, plano_id, data_interv, tipo, descricao, custo) SELECT id, maquina_id, plano_id, data_interv, tipo, descricao, custo FROM intervencao;
-- @@
DROP TABLE intervencao;
-- @@
ALTER TABLE intervencao_nova RENAME TO intervencao;
-- @@
CREATE INDEX idx_intervencao_plano   ON intervencao(plano_id, data_interv);
-- @@
CREATE INDEX idx_intervencao_maquina ON intervencao(maquina_id);
-- @@
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
-- @@
CREATE TABLE historico_estado_nova (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    maquina_id   INTEGER NOT NULL REFERENCES maquina(id),
    data_estado  TEXT NOT NULL,
    estado       TEXT NOT NULL CHECK (estado IN ('ATIVO','INOPERATIVO','MANUTENCAO','ABATIDO')),
    motivo       TEXT
);
-- @@
INSERT INTO historico_estado_nova(id, maquina_id, data_estado, estado, motivo) SELECT id, maquina_id, data_estado, estado, motivo FROM historico_estado;
-- @@
DROP TABLE historico_estado;
-- @@
ALTER TABLE historico_estado_nova RENAME TO historico_estado;
-- @@
CREATE INDEX idx_historico_maquina   ON historico_estado(maquina_id, data_estado);
-- @@
CREATE TRIGGER trg_historico_sem_update BEFORE UPDATE ON historico_estado
BEGIN
    SELECT RAISE(ABORT, 'O historico de estados so pode ser acrescentado.');
END;
-- @@
CREATE TRIGGER trg_historico_sem_delete BEFORE DELETE ON historico_estado
BEGIN
    SELECT RAISE(ABORT, 'O historico de estados so pode ser acrescentado.');
END;
