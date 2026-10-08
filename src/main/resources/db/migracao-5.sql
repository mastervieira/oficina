-- Migração 4 -> 5: o artigo passa a poder ter uma imagem (foto) guardada na própria base de dados, para ir
-- nas cópias de segurança. A coluna fica NULL nos artigos existentes. Não reconstrói a tabela.
ALTER TABLE artigo ADD COLUMN imagem BLOB;
