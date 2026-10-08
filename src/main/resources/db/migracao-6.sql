-- Migração 5 -> 6: a máquina passa a poder ter uma imagem (foto) guardada na própria base de dados, como o artigo.
-- A coluna fica NULL nas máquinas existentes. Não reconstrói a tabela.
ALTER TABLE maquina ADD COLUMN imagem BLOB;
