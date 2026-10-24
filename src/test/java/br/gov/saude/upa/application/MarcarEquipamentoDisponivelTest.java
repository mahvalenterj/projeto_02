package br.gov.saude.upa.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.saude.upa.domain.EnderecoAlternativo;
import br.gov.saude.upa.domain.Equipamento;
import br.gov.saude.upa.domain.StatusEquipamento;
import br.gov.saude.upa.domain.TipoEquipamento;
import br.gov.saude.upa.domain.TransicaoDeStatusInvalidaException;
import br.gov.saude.upa.domain.UPA;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarcarEquipamentoDisponivelTest {

  private static final UUID ID_UPA = UUID.randomUUID();
  private static final UUID ID_TOMOGRAFO = UUID.randomUUID();

  private final UPAPersistencia upas = mock(UPAPersistencia.class);
  private final MarcarEquipamentoDisponivel casoDeUso = new MarcarEquipamentoDisponivel(upas);

  private UPA upaComTomografo(StatusEquipamento status) {
    return new UPA(
        ID_UPA,
        "Madureira",
        List.of(new Equipamento(ID_TOMOGRAFO, TipoEquipamento.TOMOGRAFO, status)),
        Map.of(TipoEquipamento.TOMOGRAFO, new EnderecoAlternativo("UPA Vizinha")));
  }

  @Test
  void deveSalvarAUpaDisponivelSemGerarEvento() {
    UPA upa = upaComTomografo(StatusEquipamento.INDISPONIVEL);
    when(upas.buscar(ID_UPA)).thenReturn(Optional.of(upa));

    casoDeUso.executar(ID_UPA, ID_TOMOGRAFO);

    verify(upas).salvar(upa);
    assertThat(upa.equipamentos().get(0).status()).isEqualTo(StatusEquipamento.DISPONIVEL);
    assertThat(upa.eventosRegistrados()).isEmpty();
  }

  @Test
  void deveFalharQuandoAUpaNaoExiste() {
    when(upas.buscar(ID_UPA)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> casoDeUso.executar(ID_UPA, ID_TOMOGRAFO))
        .isInstanceOf(UpaNaoEncontradaException.class);

    verify(upas, never()).salvar(any());
  }

  @Test
  void deveFalharQuandoATransicaoEInvalida() {
    when(upas.buscar(ID_UPA))
        .thenReturn(Optional.of(upaComTomografo(StatusEquipamento.DISPONIVEL)));

    assertThatThrownBy(() -> casoDeUso.executar(ID_UPA, ID_TOMOGRAFO))
        .isInstanceOf(TransicaoDeStatusInvalidaException.class);

    verify(upas, never()).salvar(any());
  }
}
