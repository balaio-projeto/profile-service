UPDATE perfis_artesao
SET slug = 'artesao-' || conta_id::text
WHERE slug <> 'artesao-' || conta_id::text;

INSERT INTO perfis_artesao (id, conta_id, slug, nome, atelie)
SELECT gen_random_uuid(),
       c.id,
       'artesao-' || c.id::text,
       c.nome,
       left('Ateliê de ' || c.nome, 240)
FROM contas c
WHERE c.papel = 'artesao'
  AND NOT EXISTS (SELECT 1 FROM perfis_artesao p WHERE p.conta_id = c.id);
