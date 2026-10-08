-- Migração 2 -> 3: passa a ser possível apagar máquinas e artigos que nunca foram usados.
--  * desaparecem os triggers que proibiam qualquer DELETE em maquina e artigo (as chaves estrangeiras
--    continuam a recusar apagar o que tem movimentos, planos ou intervenções);
--  * o histórico de estados continua só a acrescentar, exceto a linha "Registo inicial" de uma máquina que
--    nunca mudou de estado (só se pode apagar uma linha quando é a única da máquina).
DROP TRIGGER trg_maquina_sem_delete;
-- @@
DROP TRIGGER trg_artigo_sem_delete;
-- @@
DROP TRIGGER trg_historico_sem_delete;
-- @@
CREATE TRIGGER trg_historico_sem_delete BEFORE DELETE ON historico_estado
WHEN (SELECT COUNT(*) FROM historico_estado WHERE maquina_id = OLD.maquina_id) > 1
BEGIN
    SELECT RAISE(ABORT, 'O historico de estados so pode ser acrescentado.');
END;
