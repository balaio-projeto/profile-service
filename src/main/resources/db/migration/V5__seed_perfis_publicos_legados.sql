-- Os slugs já existem nas peças iniciais do catalog-service. Estes perfis
-- são somente públicos: não criamos credenciais artificiais para os artesãos.
ALTER TABLE perfis_artesao ALTER COLUMN conta_id DROP NOT NULL;

INSERT INTO perfis_artesao
    (id, conta_id, slug, nome, atelie, historia, territorio, tecnica, imagem)
VALUES
    ('42e5b0f1-4301-4a42-bb6f-dad500000001', NULL, 'mestre-nuca',
     'Mestre Nuca (Sucessor)', 'Atelier Sucessores de Nuca',
     'O ateliê mantém viva a tradição de moldar leões de juba cacheada em cerâmica.',
     'Tracunhaém, Mata Norte de Pernambuco', 'Cerâmica & Barro', '/fotos/atelier-nuca.svg'),
    ('42e5b0f1-4301-4a42-bb6f-dad500000002', NULL, 'maria-de-caruaru',
     'Maria de Caruaru', 'Casa da Renascença',
     'Produz renda renascença sob encomenda com um grupo de bordadeiras.',
     'Cariri pernambucano', 'Bordado & Renda (Renascença)', '/fotos/casa-renascenca.svg'),
    ('42e5b0f1-4301-4a42-bb6f-dad500000003', NULL, 'mestre-dila',
     'Mestre Dila', 'Ateliê de Xilogravura do Agreste',
     'Gravador e cordelista que imprime suas matrizes à mão.',
     'Caruaru, Agreste', 'Xilogravura', '/fotos/atelie-dila.svg'),
    ('42e5b0f1-4301-4a42-bb6f-dad500000004', NULL, 'mestre-amaro',
     'Mestre Amaro', 'Oficina de Imburana',
     'Entalha figuras do cotidiano sertanejo em madeira recolhida de podas.',
     'Petrolina, Sertão do São Francisco', 'Escultura em Madeira', '/fotos/oficina-imburana.svg'),
    ('42e5b0f1-4301-4a42-bb6f-dad500000005', NULL, 'ze-do-cariri',
     'Artesão Zé do Cariri', 'Couraria do Pajeú',
     'Produz gibões, chapéus e bolsas de couro com costura à mão.',
     'Pajeú, Sertão', 'Couro Autoral', '/fotos/couraria-pajeu.svg'),
    ('42e5b0f1-4301-4a42-bb6f-dad500000006', NULL, 'dona-bia-do-ipojuca',
     'Dona Bia do Ipojuca', 'Barro do Ipojuca',
     'Pinta cenas de feira e festa em cerâmica utilitária.',
     'Vale do Ipojuca', 'Cerâmica & Barro', '/fotos/barro-ipojuca.svg'),
    ('42e5b0f1-4301-4a42-bb6f-dad500000007', NULL, 'mestre-vitalino-sucessores',
     'Sucessores de Vitalino', 'Casa do Alto do Moura',
     'A casa continua o traço de Mestre Vitalino no Alto do Moura.',
     'Alto do Moura, Caruaru', 'Cerâmica & Barro', '/fotos/alto-do-moura.svg')
ON CONFLICT (slug) DO NOTHING;
