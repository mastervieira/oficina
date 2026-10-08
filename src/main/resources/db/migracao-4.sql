-- Migração 3 -> 4: os planos de manutenção passam de uma MÁQUINA para um TIPO de máquina (categoria).
--  * cada plano por máquina passa a plano do tipo dessa máquina; planos do mesmo tipo com a mesma tarefa (ignorando
--    maiúsculas) fundem-se num só, com a periodicidade MAIS CURTA (a mais exigente);
--  * as intervenções ligadas a esses planos passam a apontar para o plano do tipo (a máquina não muda);
--  * o trigger "o plano pertence à máquina" passa a "o plano aplica-se ao tipo da máquina".
-- Reconstrói a tabela (o SQLite não altera colunas): corre com foreign_keys = OFF, numa transação, e o
-- SchemaInitializer termina com PRAGMA foreign_key_check. As definições vêm de schema.sql.

DROP TRIGGER trg_intervencao_plano_maquina;
-- @@
DROP TRIGGER trg_intervencao_plano_maquina_upd;
-- @@
CREATE TABLE plano_manutencao_nova (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    categoria_id         INTEGER NOT NULL REFERENCES categoria(id),
    tarefa               TEXT NOT NULL,
    periodicidade_meses  INTEGER NOT NULL CHECK (periodicidade_meses > 0),
    UNIQUE (categoria_id, tarefa COLLATE NOCASE)
);
-- @@
INSERT INTO plano_manutencao_nova(categoria_id, tarefa, periodicidade_meses)
SELECT m.categoria_id, MIN(p.tarefa), MIN(p.periodicidade_meses)
FROM plano_manutencao p
JOIN maquina m ON m.id = p.maquina_id
GROUP BY m.categoria_id, lower(p.tarefa)
ORDER BY m.categoria_id, MIN(p.tarefa);
-- @@
UPDATE intervencao SET plano_id = (
    SELECT n.id
    FROM plano_manutencao p
    JOIN maquina m ON m.id = p.maquina_id
    JOIN plano_manutencao_nova n ON n.categoria_id = m.categoria_id AND n.tarefa = p.tarefa COLLATE NOCASE
    WHERE p.id = intervencao.plano_id)
WHERE plano_id IS NOT NULL;
-- @@
DROP TABLE plano_manutencao;
-- @@
ALTER TABLE plano_manutencao_nova RENAME TO plano_manutencao;
-- @@
CREATE INDEX idx_plano_categoria     ON plano_manutencao(categoria_id);
-- @@
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
