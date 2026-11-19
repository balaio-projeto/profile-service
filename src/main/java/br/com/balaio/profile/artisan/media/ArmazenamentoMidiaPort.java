package br.com.balaio.profile.artisan.media;

public interface ArmazenamentoMidiaPort {
    void enviar(MidiaPerfil midia, byte[] conteudo);
    byte[] recuperar(MidiaPerfil midia);
    void excluir(MidiaPerfil midia);
}
