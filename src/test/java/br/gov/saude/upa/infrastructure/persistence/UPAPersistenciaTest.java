package br.gov.saude.upa.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.saude.upa.PostgresIntegrationTest;
import br.gov.saude.upa.application.ConflitoDeConcorrenciaException;
import br.gov.saude.upa.application.ConsultaDeEquipamento;
import br.gov.saude.upa.application.EquipamentoInexistenteException;
import br.gov.saude.upa.application.UPAPersistencia;
import br.gov.saude.upa.domain.EnderecoAlternativo;
import br.gov.saude.upa.domain.Equipamento;
import br.gov.saude.upa.domain.StatusEquipamento;
import br.gov.saude.upa.domain.TipoEquipamento;
import br.gov.saude.upa.domain.UPA;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** UPA e equipamento no Postgres real, com o {@code @Version} do equipamento. Parte de CA13. */
@Import(EscritaTestConfiguration.class)
class UPAPersistenciaTest extends PostgresIntegrationTest {

  @Autowired private UPAPersistencia upas;
  @Autowired private ConsultaDeEquipamento consulta;
  @Autowired private JdbcTemplate jdbc;

  private record Cenario(UUID idUpa, UUID tomografo, UUID raioX) {}

  private Cenario gravarUpa() {
    UUID idUpa = UUID.randomUUID();
    UUID tomografo = UUID.randomUUID();
    UUID raioX = UUID.randomUUID();
    upas.salvar(
        new UPA(
            idUpa,
            "Madureira",
            List.of(
                new Equipamento(tomografo, TipoEquipamento.TOMOGRAFO, StatusEquipamento.DISPONIVEL),
                new Equipamento(raioX, TipoEquipamento.RAIO_X, StatusEquipamento.DISPONIVEL)),
            Map.of(
                TipoEquipamento.TOMOGRAFO, new EnderecoAlternativo("UPA Vizinha, Rua A, 10"),
                TipoEquipamento.RAIO_X, new EnderecoAlternativo("Policlínica, Rua B, 20"))));
    return new Cenario(idUpa, tomografo, raioX);
  }

  private long versao(UUID idEquipamento) {
    return jdbc.queryForObject(
        "SELECT version FROM equipamento WHERE id = ?", Long.class, idEquipamento);
  }

  private String statusNoBanco(UUID idEquipamento) {
    return jdbc.queryForObject(
        "SELECT status FROM equipamento WHERE id = ?", String.class, idEquipamento);
  }

  @Test
  void deveReconstituirAUpaGravadaComEquipamentosEEnderecos() {
    Cenario c = gravarUpa();

    UPA lida = upas.buscar(c.idUpa()).orElseThrow();

    assertThat(lida.id()).isEqualTo(c.idUpa());
    assertThat(lida.nome()).isEqualTo("Madureira");
    assertThat(lida.equipamentos())
        .extracting(Equipamento::id, Equipamento::tipo, Equipamento::status)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(
                c.tomografo(), TipoEquipamento.TOMOGRAFO, StatusEquipamento.DISPONIVEL),
            org.assertj.core.groups.Tuple.tuple(
                c.raioX(), TipoEquipamento.RAIO_X, StatusEquipamento.DISPONIVEL));
    assertThat(lida.enderecoAlternativoPara(TipoEquipamento.TOMOGRAFO))
        .contains(new EnderecoAlternativo("UPA Vizinha, Rua A, 10"));
    assertThat(lida.enderecoAlternativoPara(TipoEquipamento.RAIO_X))
        .contains(new EnderecoAlternativo("Policlínica, Rua B, 20"));
  }

  @Test
  void deveDevolverVazioQuandoAUpaNaoExiste() {
    assertThat(upas.buscar(UUID.randomUUID())).isEmpty();
  }

  @Test
  void deveGravarOStatusNovoEIncrementarAVersaoDoEquipamentoAlterado() {
    Cenario c = gravarUpa();
    UPA upa = upas.buscar(c.idUpa()).orElseThrow();

    upa.marcarEquipamentoIndisponivel(c.tomografo(), UUID.randomUUID(), Instant.now());
    upas.salvar(upa);

    assertThat(statusNoBanco(c.tomografo())).isEqualTo("INDISPONIVEL");
    assertThat(versao(c.tomografo())).isEqualTo(1);
    assertThat(statusNoBanco(c.raioX())).isEqualTo("DISPONIVEL");
    assertThat(versao(c.raioX())).isZero();
  }

  @Test
  void deveManterAVersaoQuandoNadaMudou() {
    Cenario c = gravarUpa();

    upas.salvar(upas.buscar(c.idUpa()).orElseThrow());

    assertThat(versao(c.tomografo())).isZero();
  }

  @Test
  void deveAceitarDuasGravacoesSeguidasDaMesmaInstancia() {
    Cenario c = gravarUpa();
    UPA upa = upas.buscar(c.idUpa()).orElseThrow();

    upa.marcarEquipamentoIndisponivel(c.tomografo(), UUID.randomUUID(), Instant.now());
    upas.salvar(upa);
    upa.marcarEquipamentoDisponivel(c.tomografo());
    upas.salvar(upa);

    assertThat(statusNoBanco(c.tomografo())).isEqualTo("DISPONIVEL");
    assertThat(versao(c.tomografo())).isEqualTo(2);
  }

  @Test
  void deveRejeitarASegundaCopiaDaMesmaUpaQuandoAmbasAlteramOMesmoEquipamento() {
    Cenario c = gravarUpa();
    UPA primeira = upas.buscar(c.idUpa()).orElseThrow();
    UPA segunda = upas.buscar(c.idUpa()).orElseThrow();
    primeira.marcarEquipamentoIndisponivel(c.tomografo(), UUID.randomUUID(), Instant.now());
    segunda.marcarEquipamentoIndisponivel(c.tomografo(), UUID.randomUUID(), Instant.now());

    upas.salvar(primeira);

    assertThatThrownBy(() -> upas.salvar(segunda))
        .isInstanceOfSatisfying(
            ConflitoDeConcorrenciaException.class, e -> assertThat(e.idUpa()).isEqualTo(c.idUpa()));
    assertThat(versao(c.tomografo())).isEqualTo(1);
  }

  @Test
  void deveRejeitarCopiaVelhaDepoisDaVoltaParaDisponivel() {
    Cenario c = gravarUpa();
    UPA velha = upas.buscar(c.idUpa()).orElseThrow();
    UPA atual = upas.buscar(c.idUpa()).orElseThrow();
    atual.marcarEquipamentoIndisponivel(c.tomografo(), UUID.randomUUID(), Instant.now());
    upas.salvar(atual);
    atual.marcarEquipamentoDisponivel(c.tomografo());
    upas.salvar(atual);

    velha.marcarEquipamentoIndisponivel(c.tomografo(), UUID.randomUUID(), Instant.now());

    assertThatThrownBy(() -> upas.salvar(velha))
        .isInstanceOf(ConflitoDeConcorrenciaException.class);
  }

  @Test
  void deveAceitarAlteracoesDeEquipamentosDiferentesEmCopiasDiferentes() {
    Cenario c = gravarUpa();
    UPA primeira = upas.buscar(c.idUpa()).orElseThrow();
    UPA segunda = upas.buscar(c.idUpa()).orElseThrow();
    primeira.marcarEquipamentoIndisponivel(c.tomografo(), UUID.randomUUID(), Instant.now());
    segunda.marcarEquipamentoIndisponivel(c.raioX(), UUID.randomUUID(), Instant.now());

    upas.salvar(primeira);
    upas.salvar(segunda);

    assertThat(statusNoBanco(c.tomografo())).isEqualTo("INDISPONIVEL");
    assertThat(statusNoBanco(c.raioX())).isEqualTo("INDISPONIVEL");
  }

  @Test
  void deveDeixarSoUmaCopiaVencerQuandoVariasDisputamOMesmoEquipamento() throws Exception {
    Cenario c = gravarUpa();

    List<UPA> copias = new java.util.ArrayList<>();
    for (int i = 0; i < 6; i++) {
      UPA copia = upas.buscar(c.idUpa()).orElseThrow();
      copia.marcarEquipamentoIndisponivel(c.tomografo(), UUID.randomUUID(), Instant.now());
      copias.add(copia);
    }

    List<Boolean> resultados =
        Disputa.largarJuntas(
            6,
            i -> {
              try {
                upas.salvar(copias.get(i));
                return true;
              } catch (ConflitoDeConcorrenciaException e) {
                return false;
              }
            });

    assertThat(Disputa.vencedores(resultados)).isEqualTo(1);
    assertThat(versao(c.tomografo())).isEqualTo(1);
  }

  @Test
  void deveLerOStatusAtualSemCacheMesmoQuandoOBancoMudaPorFora() {
    Cenario c = gravarUpa();
    assertThat(consulta.statusAtual(c.tomografo())).isEqualTo(StatusEquipamento.DISPONIVEL);

    jdbc.update("UPDATE equipamento SET status = 'INDISPONIVEL' WHERE id = ?", c.tomografo());

    assertThat(consulta.statusAtual(c.tomografo())).isEqualTo(StatusEquipamento.INDISPONIVEL);
  }

  @Test
  void deveFalharAoConsultarEquipamentoInexistente() {
    UUID inexistente = UUID.randomUUID();

    assertThatThrownBy(() -> consulta.statusAtual(inexistente))
        .isInstanceOf(EquipamentoInexistenteException.class)
        .hasMessageContaining(inexistente.toString());
  }

  @Test
  void deveRejeitarStatusEmManutencaoSomenteSeOBancoPermitirOValorConhecido() {
    Cenario c = gravarUpa();

    jdbc.update("UPDATE equipamento SET status = 'EM_MANUTENCAO' WHERE id = ?", c.raioX());

    assertThat(consulta.statusAtual(c.raioX())).isEqualTo(StatusEquipamento.EM_MANUTENCAO);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE equipamento SET status = 'QUEBRADO' WHERE id = ?", c.raioX()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }
}
