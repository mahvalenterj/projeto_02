package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  private static final String NOME_UPA = "Madureira";

  private static UPA novaUpa(List<Equipamento> equipamentos) {
    return new UPA(ID_UPA, NOME_UPA, equipamentos, Map.of());
  }

  private static UPA upaComTomografoDisponivel() {
    return novaUpa(List.of(tomografo(StatusEquipamento.DISPONIVEL)));
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
                      UPA upa = novaUpa(List.of(tomografo(status)));

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
    UPA upa = novaUpa(List.of(tomografo(StatusEquipamento.EM_MANUTENCAO)));

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
        novaUpa(
            List.of(tomografo(StatusEquipamento.DISPONIVEL), raioX(StatusEquipamento.DISPONIVEL)));

    upa.marcarEquipamentoIndisponivel(ID_TOMOGRAFO, ID_EVENTO, MOMENTO_FALHA);

    assertThat(statusDe(upa, ID_TOMOGRAFO)).isEqualTo(StatusEquipamento.INDISPONIVEL);
    assertThat(statusDe(upa, ID_RAIO_X)).isEqualTo(StatusEquipamento.DISPONIVEL);
  }

  @Test
  void deveRejeitarEquipamentosComIdDuplicado() {
    List<Equipamento> duplicados =
        List.of(tomografo(StatusEquipamento.DISPONIVEL), tomografo(StatusEquipamento.DISPONIVEL));

    assertThatThrownBy(() -> novaUpa(duplicados))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(ID_TOMOGRAFO.toString());
  }

  @Test
  void deveRejeitarArgumentosNulosNaConstrucao() {
    assertThatThrownBy(() -> new UPA(null, NOME_UPA, List.of(), Map.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new UPA(ID_UPA, null, List.of(), Map.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new UPA(ID_UPA, NOME_UPA, null, Map.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new UPA(ID_UPA, NOME_UPA, List.of(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void deveRejeitarNomeEmBranco() {
    assertThatThrownBy(() -> new UPA(ID_UPA, "", List.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new UPA(ID_UPA, "   ", List.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deveExporONomeDaUpa() {
    assertThat(upaComTomografoDisponivel().nome()).isEqualTo(NOME_UPA);
  }

  @Test
  void deveExporIdDaUpa() {
    assertThat(upaComTomografoDisponivel().id()).isEqualTo(ID_UPA);
  }

  @Test
  void deveCopiarListaDeEquipamentosNaConstrucao() {
    List<Equipamento> original = new ArrayList<>();
    original.add(tomografo(StatusEquipamento.DISPONIVEL));
    UPA upa = novaUpa(original);

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

  private static final UUID ID_EVENTO_2 = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
  private static final Instant MOMENTO_FALHA_2 = Instant.parse("2026-10-11T15:00:00Z");

  private static UPA upaComTomografo(StatusEquipamento status) {
    return novaUpa(List.of(tomografo(status)));
  }

  @Test
  void deveMarcarEquipamentoComoDisponivelQuandoEstiverIndisponivel() {
    UPA upa = upaComTomografo(StatusEquipamento.INDISPONIVEL);

    upa.marcarEquipamentoDisponivel(ID_TOMOGRAFO);

    assertThat(statusDe(upa, ID_TOMOGRAFO)).isEqualTo(StatusEquipamento.DISPONIVEL);
  }

  @Test
  void naoDeveRegistrarEventoQuandoEquipamentoVoltarADisponivel() {
    UPA upa = upaComTomografoDisponivel();
    upa.marcarEquipamentoIndisponivel(ID_TOMOGRAFO, ID_EVENTO, MOMENTO_FALHA);

    upa.marcarEquipamentoDisponivel(ID_TOMOGRAFO);

    assertThat(upa.eventosRegistrados())
        .containsExactly(
            new EquipamentoIndisponivelEvent(ID_EVENTO, ID_TOMOGRAFO, ID_UPA, MOMENTO_FALHA));
  }

  @TestFactory
  Stream<DynamicTest> deveLancarTransicaoInvalidaQuandoVoltarADisponivelSemEstarIndisponivel() {
    return Stream.of(StatusEquipamento.DISPONIVEL, StatusEquipamento.EM_MANUTENCAO)
        .map(
            status ->
                DynamicTest.dynamicTest(
                    "a partir de " + status,
                    () -> {
                      UPA upa = upaComTomografo(status);

                      assertThatThrownBy(() -> upa.marcarEquipamentoDisponivel(ID_TOMOGRAFO))
                          .isInstanceOf(TransicaoDeStatusInvalidaException.class)
                          .hasMessageContaining(ID_TOMOGRAFO.toString())
                          .hasMessageContaining(status.name())
                          .hasMessageContaining(StatusEquipamento.DISPONIVEL.name());

                      assertThat(statusDe(upa, ID_TOMOGRAFO)).isEqualTo(status);
                      assertThat(upa.eventosRegistrados()).isEmpty();
                    }));
  }

  @Test
  void deveLancarNaoEncontradoQuandoVoltarADisponivelEquipamentoDeOutraUpa() {
    UPA upa = upaComTomografo(StatusEquipamento.INDISPONIVEL);

    assertThatThrownBy(() -> upa.marcarEquipamentoDisponivel(ID_RAIO_X))
        .isInstanceOf(EquipamentoNaoEncontradoException.class)
        .hasMessageContaining(ID_RAIO_X.toString())
        .hasMessageContaining(ID_UPA.toString());
  }

  @Test
  void deveRegistrarNovoEventoComNovoEventoIdQuandoEquipamentoCairDeNovo() {
    UPA upa = upaComTomografoDisponivel();
    upa.marcarEquipamentoIndisponivel(ID_TOMOGRAFO, ID_EVENTO, MOMENTO_FALHA);
    upa.marcarEquipamentoDisponivel(ID_TOMOGRAFO);

    upa.marcarEquipamentoIndisponivel(ID_TOMOGRAFO, ID_EVENTO_2, MOMENTO_FALHA_2);

    assertThat(statusDe(upa, ID_TOMOGRAFO)).isEqualTo(StatusEquipamento.INDISPONIVEL);
    assertThat(upa.eventosRegistrados())
        .containsExactly(
            new EquipamentoIndisponivelEvent(ID_EVENTO, ID_TOMOGRAFO, ID_UPA, MOMENTO_FALHA),
            new EquipamentoIndisponivelEvent(ID_EVENTO_2, ID_TOMOGRAFO, ID_UPA, MOMENTO_FALHA_2));
  }

  @Test
  void deveAlterarSoOEquipamentoIndicadoAoVoltarADisponivel() {
    UPA upa =
        novaUpa(
            List.of(
                tomografo(StatusEquipamento.INDISPONIVEL), raioX(StatusEquipamento.INDISPONIVEL)));

    upa.marcarEquipamentoDisponivel(ID_TOMOGRAFO);

    assertThat(statusDe(upa, ID_TOMOGRAFO)).isEqualTo(StatusEquipamento.DISPONIVEL);
    assertThat(statusDe(upa, ID_RAIO_X)).isEqualTo(StatusEquipamento.INDISPONIVEL);
  }

  @Test
  void deveRejeitarIdNuloAoVoltarADisponivel() {
    UPA upa = upaComTomografo(StatusEquipamento.INDISPONIVEL);

    assertThatThrownBy(() -> upa.marcarEquipamentoDisponivel(null))
        .isInstanceOf(NullPointerException.class);
  }

  private static final EnderecoAlternativo ENDERECO_TOMOGRAFO =
      new EnderecoAlternativo("UPA Cascadura, Rua Carolina Machado, 100");

  @Test
  void deveDevolverOEnderecoAlternativoDoTipoQuandoConfigurado() {
    UPA upa =
        new UPA(
            ID_UPA,
            NOME_UPA,
            List.of(tomografo(StatusEquipamento.DISPONIVEL), raioX(StatusEquipamento.DISPONIVEL)),
            Map.of(TipoEquipamento.TOMOGRAFO, ENDERECO_TOMOGRAFO));

    assertThat(upa.enderecoAlternativoPara(TipoEquipamento.TOMOGRAFO)).contains(ENDERECO_TOMOGRAFO);
    assertThat(upa.enderecoAlternativoPara(TipoEquipamento.RAIO_X)).isEmpty();
  }

  @Test
  void deveCopiarOMapaDeEnderecosAlternativosNaConstrucao() {
    Map<TipoEquipamento, EnderecoAlternativo> original = new HashMap<>();
    original.put(TipoEquipamento.TOMOGRAFO, ENDERECO_TOMOGRAFO);
    UPA upa = new UPA(ID_UPA, NOME_UPA, List.of(), original);

    original.clear();

    assertThat(upa.enderecoAlternativoPara(TipoEquipamento.TOMOGRAFO)).contains(ENDERECO_TOMOGRAFO);
  }

  @Test
  void deveRejeitarTipoNuloAoBuscarEnderecoAlternativo() {
    assertThatThrownBy(() -> upaComTomografoDisponivel().enderecoAlternativoPara(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void deveInformarOTipoDoEquipamento() {
    UPA upa =
        novaUpa(
            List.of(tomografo(StatusEquipamento.DISPONIVEL), raioX(StatusEquipamento.DISPONIVEL)));

    assertThat(upa.tipoDoEquipamento(ID_TOMOGRAFO)).isEqualTo(TipoEquipamento.TOMOGRAFO);
    assertThat(upa.tipoDoEquipamento(ID_RAIO_X)).isEqualTo(TipoEquipamento.RAIO_X);
  }

  @Test
  void deveLancarNaoEncontradoAoInformarTipoDeEquipamentoDeOutraUpa() {
    UPA upa = upaComTomografoDisponivel();

    assertThatThrownBy(() -> upa.tipoDoEquipamento(ID_RAIO_X))
        .isInstanceOf(EquipamentoNaoEncontradoException.class)
        .hasMessageContaining(ID_RAIO_X.toString());
    assertThatThrownBy(() -> upa.tipoDoEquipamento(null)).isInstanceOf(NullPointerException.class);
  }
}
