package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class UPATest {

  private static final UUID ID_UPA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID ID_TOMOGRAFO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final UUID ID_RAIO_X = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
  private static final UUID ID_EVENTO = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final Instant MOMENTO_FALHA = Instant.parse("2026-10-11T12:00:00Z");

  private static Equipamento tomografo(StatusEquipamento status) {
    return new Equipamento(ID_TOMOGRAFO, TipoEquipamento.TOMOGRAFO, status);
  }

  private static Equipamento raioX(StatusEquipamento status) {
    return new Equipamento(ID_RAIO_X, TipoEquipamento.RAIO_X, status);
  }

  private static UPA upaComTomografoDisponivel() {
    return new UPA(ID_UPA, List.of(tomografo(StatusEquipamento.DISPONIVEL)));
  }

  private static StatusEquipamento statusDe(UPA upa, UUID idEquipamento) {
    return upa.equipamentos().stream()
        .filter(equipamento -> equipamento.id().equals(idEquipamento))
        .findFirst()
        .orElseThrow()
        .status();
  }

  @Test
  void deveMarcarEquipamentoComoIndisponivelQuandoEstiverDisponivel() {
    UPA upa = upaComTomografoDisponivel();

    upa.marcarEquipamentoIndisponivel(ID_TOMOGRAFO, ID_EVENTO, MOMENTO_FALHA);

    assertThat(statusDe(upa, ID_TOMOGRAFO)).isEqualTo(StatusEquipamento.INDISPONIVEL);
  }

  @TestFactory
  Stream<DynamicTest> deveLancarTransicaoInvalidaQuandoEquipamentoNaoEstiverDisponivel() {
    return Stream.of(StatusEquipamento.INDISPONIVEL, StatusEquipamento.EM_MANUTENCAO)
        .map(
            status ->
                DynamicTest.dynamicTest(
                    "a partir de " + status,
                    () -> {
                      UPA upa = new UPA(ID_UPA, List.of(tomografo(status)));

                      assertThatThrownBy(
                              () ->
                                  upa.marcarEquipamentoIndisponivel(
                                      ID_TOMOGRAFO, ID_EVENTO, MOMENTO_FALHA))
                          .isInstanceOf(TransicaoDeStatusInvalidaException.class)
                          .hasMessageContaining(ID_TOMOGRAFO.toString())
                          .hasMessageContaining(status.name());
                    }));
  }

  @Test
  void deveLancarNaoEncontradoQuandoEquipamentoNaoPertencerAUpa() {
    UPA upa = upaComTomografoDisponivel();

    assertThatThrownBy(() -> upa.marcarEquipamentoIndisponivel(ID_RAIO_X, ID_EVENTO, MOMENTO_FALHA))
        .isInstanceOf(EquipamentoNaoEncontradoException.class)
        .hasMessageContaining(ID_RAIO_X.toString())
        .hasMessageContaining(ID_UPA.toString());
  }

  @Test
  void deveRegistrarEventoComIdsEMomentoQuandoMarcarIndisponivel() {
    UPA upa = upaComTomografoDisponivel();

    upa.marcarEquipamentoIndisponivel(ID_TOMOGRAFO, ID_EVENTO, MOMENTO_FALHA);

    assertThat(upa.eventosRegistrados())
        .containsExactly(
            new EquipamentoIndisponivelEvent(ID_EVENTO, ID_TOMOGRAFO, ID_UPA, MOMENTO_FALHA));
  }

  @Test
  void naoDeveAlterarEstadoNemRegistrarEventoQuandoTransicaoForInvalida() {
    UPA upa = new UPA(ID_UPA, List.of(tomografo(StatusEquipamento.EM_MANUTENCAO)));

    assertThatThrownBy(
            () -> upa.marcarEquipamentoIndisponivel(ID_TOMOGRAFO, ID_EVENTO, MOMENTO_FALHA))
        .isInstanceOf(TransicaoDeStatusInvalidaException.class);

    assertThat(statusDe(upa, ID_TOMOGRAFO)).isEqualTo(StatusEquipamento.EM_MANUTENCAO);
    assertThat(upa.eventosRegistrados()).isEmpty();
  }

  @Test
  void naoDeveAlterarEstadoNemRegistrarEventoQuandoEventoIdOuMomentoForemNulos() {
    UPA upa = upaComTomografoDisponivel();

    assertThatThrownBy(() -> upa.marcarEquipamentoIndisponivel(ID_TOMOGRAFO, null, MOMENTO_FALHA))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> upa.marcarEquipamentoIndisponivel(ID_TOMOGRAFO, ID_EVENTO, null))
        .isInstanceOf(NullPointerException.class);

    assertThat(statusDe(upa, ID_TOMOGRAFO)).isEqualTo(StatusEquipamento.DISPONIVEL);
    assertThat(upa.eventosRegistrados()).isEmpty();
  }

  @Test
  void deveRejeitarIdDoEquipamentoNulo() {
    UPA upa = upaComTomografoDisponivel();

    assertThatThrownBy(() -> upa.marcarEquipamentoIndisponivel(null, ID_EVENTO, MOMENTO_FALHA))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void deveAlterarSoOEquipamentoIndicado() {
    UPA upa =
        new UPA(
            ID_UPA,
            List.of(tomografo(StatusEquipamento.DISPONIVEL), raioX(StatusEquipamento.DISPONIVEL)));

    upa.marcarEquipamentoIndisponivel(ID_TOMOGRAFO, ID_EVENTO, MOMENTO_FALHA);

    assertThat(statusDe(upa, ID_TOMOGRAFO)).isEqualTo(StatusEquipamento.INDISPONIVEL);
    assertThat(statusDe(upa, ID_RAIO_X)).isEqualTo(StatusEquipamento.DISPONIVEL);
  }

  @Test
  void deveRejeitarEquipamentosComIdDuplicado() {
    List<Equipamento> duplicados =
        List.of(tomografo(StatusEquipamento.DISPONIVEL), tomografo(StatusEquipamento.DISPONIVEL));

    assertThatThrownBy(() -> new UPA(ID_UPA, duplicados))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(ID_TOMOGRAFO.toString());
  }

  @Test
  void deveRejeitarIdOuEquipamentosNulosNaConstrucao() {
    assertThatThrownBy(() -> new UPA(null, List.of())).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new UPA(ID_UPA, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void deveExporIdDaUpa() {
    assertThat(upaComTomografoDisponivel().id()).isEqualTo(ID_UPA);
  }

  @Test
  void deveCopiarListaDeEquipamentosNaConstrucao() {
    List<Equipamento> original = new ArrayList<>();
    original.add(tomografo(StatusEquipamento.DISPONIVEL));
    UPA upa = new UPA(ID_UPA, original);

    original.add(raioX(StatusEquipamento.DISPONIVEL));

    assertThat(upa.equipamentos()).hasSize(1);
  }

  @Test
  void deveDevolverListasImutaveis() {
    UPA upa = upaComTomografoDisponivel();
    upa.marcarEquipamentoIndisponivel(ID_TOMOGRAFO, ID_EVENTO, MOMENTO_FALHA);

    assertThatThrownBy(() -> upa.equipamentos().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> upa.eventosRegistrados().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void deveLimparEventosAposConsumo() {
    UPA upa = upaComTomografoDisponivel();
    upa.marcarEquipamentoIndisponivel(ID_TOMOGRAFO, ID_EVENTO, MOMENTO_FALHA);
    List<EquipamentoIndisponivelEvent> consumidos = upa.eventosRegistrados();

    upa.limparEventos();

    assertThat(upa.eventosRegistrados()).isEmpty();
    assertThat(consumidos).hasSize(1);
  }
}
