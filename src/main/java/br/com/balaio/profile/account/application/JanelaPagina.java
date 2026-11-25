package br.com.balaio.profile.account.application;

public record JanelaPagina(int pagina, int tamanho, long total, int totalPaginas) {
    public static final int TAMANHO_PADRAO = 10;
    public static final int TAMANHO_MAXIMO = 50;
    public static final int LIMITE_BUSCA = 100;

    public static JanelaPagina de(Integer paginaSolicitada, Integer tamanhoSolicitado, long total) {
        int tamanho = tamanhoSolicitado == null || tamanhoSolicitado < 1
                ? TAMANHO_PADRAO
                : Math.min(TAMANHO_MAXIMO, tamanhoSolicitado);
        int totalPaginas = (int) Math.max(1, (total + tamanho - 1) / tamanho);
        int pagina = Math.min(paginaSolicitada == null ? 1 : Math.max(1, paginaSolicitada), totalPaginas);
        return new JanelaPagina(pagina, tamanho, total, totalPaginas);
    }

    public int deslocamento() {
        return (pagina - 1) * tamanho;
    }

    public static String padraoBusca(String busca) {
        String termo = busca == null ? "" : busca.strip();
        if (termo.isEmpty()) {
            return null;
        }
        return "%" + termo.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    public static boolean buscaValida(String busca) {
        return busca == null || busca.strip().codePointCount(0, busca.strip().length()) <= LIMITE_BUSCA;
    }
}
