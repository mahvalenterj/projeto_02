package br.gov.saude.upa.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.gov.saude.upa.domain.EnderecoAlternativo;
import br.gov.saude.upa.domain.Equipamento;
import br.gov.saude.upa.domain.EquipamentoIndisponivelEvent;
import br.gov.saude.upa.domain.EquipamentoNaoEncontradoException;
import br.gov.saude.upa.domain.StatusEquipamento;
import br.gov.saude.upa.domain.TipoEquipamento;
import br.gov.saude.upa.domain.TransicaoDeStatusInvalidaException;
import br.gov.saude.upa.domain.UPA;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class MarcarEquipamentoIndisponivelTest {

  private static final Instant AGORA = Instant.parse("2026-10-11T15:00:00Z");
  private static final UUID ID_UPA = UUID.randomUUID();
  private static final UUID ID_TOMOGRAFO = UUID.randomUUID();

  private final UPAPersistencia upas = mock(UPAPersistencia.class);
  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final MarcarEquipamentoIndisponivel casoDeUso =
      new MarcarEquipamentoIndisponivel(upas, outbox, Clock.fixed(AGORA, ZoneOffset.UTC));

  private UPA upaComTomografo(StatusEquipamento status) {
    return new UPA(
        ID_UPA,
        "Madureira",
        List.of(new Equipamento(ID_TOMOGRAFO, TipoEquipamento.TOMOGRAFO, status)),
        Map.of(TipoEquipamento.TOMOGRAFO, new EnderecoAlternativo("UPA Vizinha")));
  }

  @Test
  void deveSalvarAUpaEGravarOEventoNoOutboxQuandoMarcarIndisponivel() {
    UPA upa = upaComTomografo(StatusEquipamento.DISPONIVEL);
    when(upas.buscar(ID_UPA)).thenReturn(Optional.of(upa));

    UUID eventoId = casoDeUso.executar(ID_UPA, ID_TOMOGRAFO);

    InOrder ordem = inOrder(upas, outbox);
    ordem.verify(upas).salvar(upa);
    ArgumentCaptor<EquipamentoIndisponivelEvent> gravado =
        ArgumentCaptor.forClass(EquipamentoIndisponivelEvent.class);
    ordem.verify(outbox).gravar(gravado.capture());
    assertThat(gravado.getValue())
        .isEqualTo(new EquipamentoIndisponivelEvent(eventoId, ID_TOMOGRAFO, ID_UPA, AGORA));
    assertThat(upa.equipamentos().get(0).status()).isEqualTo(StatusEquipamento.INDISPONIVEL);
  }

  @Test
  void deveLimparOsEventosDoAgregadoDepoisDeGravarNoOutbox() {
    UPA upa = upaComTomografo(StatusEquipamento.DISPONIVEL);
    when(upas.buscar(ID_UPA)).thenReturn(Optional.of(upa));

    casoDeUso.executar(ID_UPA, ID_TOMOGRAFO);

    assertThat(upa.eventosRegistrados()).isEmpty();
  }

  @Test
  void deveGerarNovoEventoIdQuandoOEquipamentoCairDeNovoDepoisDeVoltar() {
    UPA upa = upaComTomografo(StatusEquipamento.DISPONIVEL);
    when(upas.buscar(ID_UPA)).thenReturn(Optional.of(upa));
    List<EquipamentoIndisponivelEvent> gravados = new ArrayList<>();
    org.mockito.Mockito.doAnswer(
            invocacao -> {
              gravados.add(invocacao.getArgument(0));
              return null;
            })
        .when(outbox)
        .gravar(any());

    UUID primeiro = casoDeUso.executar(ID_UPA, ID_TOMOGRAFO);
    upa.marcarEquipamentoDisponivel(ID_TOMOGRAFO);
    UUID segundo = casoDeUso.executar(ID_UPA, ID_TOMOGRAFO);

    assertThat(segundo).isNotEqualTo(primeiro);
    assertThat(gravados).extracting(EquipamentoIndisponivelEvent::eventoId).hasSize(2);
    assertThat(gravados.get(0).eventoId()).isNotEqualTo(gravados.get(1).eventoId());
  }

  @Test
  void deveFalharSemEfeitoQuandoAUpaNaoExiste() {
    when(upas.buscar(ID_UPA)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> casoDeUso.executar(ID_UPA, ID_TOMOGRAFO))
        .isInstanceOfSatisfying(
            UpaNaoEncontradaException.class, e -> assertThat(e.idUpa()).isEqualTo(ID_UPA));

    verifyNoInteractions(outbox);
  }

  @Test
  void deveFalharSemEfeitoQuandoOEquipamentoNaoPertenceAUpa() {
    when(upas.buscar(ID_UPA))
        .thenReturn(Optional.of(upaComTomografo(StatusEquipamento.DISPONIVEL)));

    assertThatThrownBy(() -> casoDeUso.executar(ID_UPA, UUID.randomUUID()))
        .isInstanceOf(EquipamentoNaoEncontradoException.class);

    verify(upas, org.mockito.Mockito.never()).salvar(any());
    verifyNoInteractions(outbox);
  }

  @Test
  void deveFalharSemEfeitoQuandoATransicaoEInvalida() {
    when(upas.buscar(ID_UPA))
        .thenReturn(Optional.of(upaComTomografo(StatusEquipamento.INDISPONIVEL)));

    assertThatThrownBy(() -> casoDeUso.executar(ID_UPA, ID_TOMOGRAFO))
        .isInstanceOf(TransicaoDeStatusInvalidaException.class);

    verify(upas, org.mockito.Mockito.never()).salvar(any());
    verifyNoInteractions(outbox);
  }

  @Test
  void devePropagarOConflitoDeConcorrenciaSemGravarNoOutbox() {
    UPA upa = upaComTomografo(StatusEquipamento.DISPONIVEL);
    when(upas.buscar(ID_UPA)).thenReturn(Optional.of(upa));
    doThrow(new ConflitoDeConcorrenciaException(ID_UPA)).when(upas).salvar(upa);

    assertThatThrownBy(() -> casoDeUso.executar(ID_UPA, ID_TOMOGRAFO))
        .isInstanceOf(ConflitoDeConcorrenciaException.class);

    verifyNoInteractions(outbox);
  }
}
