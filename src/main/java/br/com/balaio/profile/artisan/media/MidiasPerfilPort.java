package br.com.balaio.profile.artisan.media;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MidiasPerfilPort {
    void reservar(MidiaPerfil midia);
    void ativar(MidiaPerfil midia);
    void remover(UUID perfilId);
    Optional<MidiaPerfil> vinculada(UUID id);
    List<MidiaPerfil> candidatasLimpeza();
    boolean prepararExclusao(MidiaPerfil midia);
    void concluirExclusao(UUID id);
}
